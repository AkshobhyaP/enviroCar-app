/**
 * Copyright (C) 2013 - 2015 the enviroCar community
 *
 * This file is part of the enviroCar app.
 *
 * The enviroCar app is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The enviroCar app is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with the enviroCar app. If not, see http://www.gnu.org/licenses/.
 */
package org.envirocar.app.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.squareup.otto.Bus;
import com.squareup.otto.Subscribe;

import org.envirocar.app.CommandListener;
import org.envirocar.app.R;
import org.envirocar.app.events.TrackDetailsProvider;
import org.envirocar.app.handler.BluetoothHandler;
import org.envirocar.app.handler.LocationHandler;
import org.envirocar.app.handler.PreferencesHandler;
import org.envirocar.core.events.gps.GpsLocationChangedEvent;
import org.envirocar.core.events.gps.GpsSatelliteFix;
import org.envirocar.core.events.gps.GpsSatelliteFixEvent;
import org.envirocar.core.injection.Injector;
import org.envirocar.core.logging.Logger;
import org.envirocar.core.utils.BroadcastUtils;
import org.envirocar.obd.bluetooth.BluetoothSocketWrapper;
import org.envirocar.obd.bluetooth.FallbackBluetoothSocket;
import org.envirocar.obd.bluetooth.NativeBluetoothSocket;
import org.envirocar.obd.events.BluetoothServiceStateChangedEvent;
import org.envirocar.obd.events.SpeedUpdateEvent;
import org.envirocar.obd.protocol.ConnectionListener;
import org.envirocar.obd.protocol.OBDCommandLooper;
import org.envirocar.obd.service.BluetoothServiceState;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import rx.Observable;
import rx.Scheduler;
import rx.Subscriber;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action0;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;

/**
 * @author dewall
 */
public class OBDConnectionService extends Service {
    private static final Logger LOG = Logger.getLogger(OBDConnectionService.class);

    protected static final int MAX_RECONNECT_COUNT = 2;
    public static final int BG_NOTIFICATION_ID = 42;

    private static final UUID EMBEDDED_BOARD_SPP = UUID
            .fromString("00001101-0000-1000-8000-00805F9B34FB");

    public static BluetoothServiceState CURRENT_SERVICE_STATE = BluetoothServiceState
            .SERVICE_STOPPED;

    // Injected fields.
    @Inject
    protected Bus mBus;
    @Inject
    protected BluetoothHandler mBluetoothHandler;
    @Inject
    protected LocationHandler mLocationHandler;
    @Inject
    protected TrackDetailsProvider mTrackDetailsProvider;

    // Text to speech variables.
    private TextToSpeech mTTS;
    private boolean mIsTTSAvailable;
    private boolean mIsTTSPrefChecked;

    // Member fields required for the connection to the OBD device.
    private CommandListener mCommandListener;
    private OBDCommandLooper mOBDCommandLooper;
    private OBDBluetoothConnection mOBDConnection;

    // Different subscriptions
    private CompositeSubscription subscriptions = new CompositeSubscription();
    private Subscription mUUIDSubscription;


    // This satellite fix indicates that there is no satellite connection yet.
    private GpsSatelliteFix mCurrentGpsSatelliteFix = new GpsSatelliteFix(0, false);
    private BluetoothServiceState mServiceState = BluetoothServiceState.SERVICE_STOPPED;

    private IBinder mBinder = new OBDConnectionBinder();

    private PowerManager.WakeLock mWakeLock;

    private OBDConnectionRecognizer connectionRecognizer = new OBDConnectionRecognizer();

    @Override
    public void onCreate() {
        super.onCreate();

        // Inject ourselves.
        ((Injector) getApplicationContext()).injectObjects(this);

        // register on the event bus
        this.mBus.register(this);
        this.mBus.register(mTrackDetailsProvider);
        this.mBus.register(connectionRecognizer);

        mTTS = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    mIsTTSAvailable = true;
                    mTTS.setLanguage(Locale.ENGLISH);
                } else {
                    LOG.warn("TextToSpeech is not available.");
                }
            }
        });

        subscriptions.add(
                PreferencesHandler.getTextToSpeechObservable(getApplicationContext())
                        .subscribe(aBoolean -> {
                            mIsTTSPrefChecked = aBoolean;
                        }));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Start the location
        mLocationHandler.startLocating();

        // Acquire the wake lock for keeping the CPU active.
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wakelock");
        mWakeLock.acquire();

        //
        doTextToSpeech("Establishing connection");

        // Get the default device
        BluetoothDevice device = mBluetoothHandler.getSelectedBluetoothDevice();

        if (device != null) {
            LOG.info("Start the OBD connection");

            // Start the OBD Connection.
            startOBDConnection(device);
        } else {
            LOG.severe("No default Bluetooth device selected");
        }

        return START_STICKY;
    }

    /**
     * Sets the current remoteService state and fire an event on the bus.
     *
     * @param state the state of the remoteService.
     */
    private void setBluetoothServiceState(BluetoothServiceState state) {
        // Set the new remoteService state
        this.mServiceState = state;
        CURRENT_SERVICE_STATE = state; // TODO FIX
        // and fire an event on the event bus.
        this.mBus.post(produceBluetoothServiceStateChangedEvent());
    }

    //    @Produce
    public BluetoothServiceStateChangedEvent produceBluetoothServiceStateChangedEvent() {
        LOG.info(String.format("produceBluetoothServiceStateChangedEvent(): %s",
                mServiceState.toString()));
        return new BluetoothServiceStateChangedEvent(mServiceState);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }


    @Override
    public void onDestroy() {
        LOG.info("onDestroy()");
        super.onDestroy();

        if (subscriptions != null)
            subscriptions.unsubscribe();

        // If there is an active UUID subscription.
        if (mUUIDSubscription != null)
            mUUIDSubscription.unsubscribe();

        // Stop this remoteService and emove this remoteService from foreground state.
        stopOBDConnection();
        stopForeground(true);

        if (mWakeLock != null)
            mWakeLock.release();

        // Unregister from the event bus.
        mBus.unregister(this);
        mBus.unregister(mTrackDetailsProvider);
        mBus.unregister(connectionRecognizer);
        mTrackDetailsProvider.onOBDConnectionStopped();
    }

    @Subscribe
    public void onReceiveGpsSatelliteFixEvent(GpsSatelliteFixEvent event) {
        boolean isFix = event.mGpsSatelliteFix.isFix();
        if (isFix != mCurrentGpsSatelliteFix.isFix()) {
            if (isFix) {
                doTextToSpeech("GPS positioning established");
            } else {
                doTextToSpeech("GPS positioning lost. Try to move the phone");
            }
            this.mCurrentGpsSatelliteFix = event.mGpsSatelliteFix;
        }
    }

    private void doTextToSpeech(String string) {
        if (mIsTTSAvailable && mIsTTSPrefChecked) {
            mTTS.speak("enviro car ".concat(string), TextToSpeech.QUEUE_ADD, null);
        }
    }

    /**
     * @param uuids
     * @return
     */
    private Observable<UUID> transformUUID(final Parcelable uuids[]) {
        return Observable.create(new Observable.OnSubscribe<UUID>() {
            @Override
            public void call(Subscriber<? super UUID> subscriber) {
                // Create a uuid for every string and return it
                for (Parcelable uuid : uuids) {
                    subscriber.onNext(UUID.fromString(uuid.toString()));
                }
                subscriber.onCompleted();
            }
        });
    }


    /**
     * @param device
     * @return
     */
    private Observable<List<UUID>> getUUIDList(final BluetoothDevice device) {
        LOG.info(String.format("getUUIDList(%s)", device.getName()));

        return BroadcastUtils.createBroadcastObservable(getApplicationContext(),
                new IntentFilter(BluetoothDevice.ACTION_UUID))
                .map(intent -> {
                    LOG.info("getUUIDList(): map call");

                    // Get the device and the UUID provided by the incoming intent.
                    BluetoothDevice deviceExtra = intent
                            .getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    Parcelable[] uuidExtra = intent
                            .getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);

                    // If the received broadcast does not belong to this receiver,
                    // skip it.
                    if (!deviceExtra.getAddress().equals(device.getAddress()))
                        return null;

                    // Result list to return
                    List<UUID> res = new ArrayList<UUID>();

                    LOG.info(String.format("Adding default UUID: %s", EMBEDDED_BOARD_SPP));
                    res.add(EMBEDDED_BOARD_SPP);

                    // Create a uuid for every string and return it
                    for (Parcelable uuid : uuidExtra) {
                        UUID next = UUID.fromString(uuid.toString());
                        if (!res.contains(next)) {
                            res.add(next);
                        }
                    }

                    // return the result list
                    return res;
                });
    }


    /**
     * @param device the device to start a connection to.
     */
    private void startOBDConnection(final BluetoothDevice device) {
        LOG.info("startOBDConnection");

        // Set remoteService state to STARTING and fire an event on the bus.
        setBluetoothServiceState(BluetoothServiceState.SERVICE_STARTING);

        if (device.fetchUuidsWithSdp())
            mUUIDSubscription = getUUIDList(device)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(uuids -> {
                        Log.i("yea", "start bluetooth connection thread");
                        (mOBDConnection = new OBDBluetoothConnection(device, uuids)).start();
                        mUUIDSubscription.unsubscribe();
                    });
    }

    /**
     * Method that stops the remoteService, removes everything from the waiting list
     */
    private void stopOBDConnection() {
        LOG.info("stopOBDConnection called");
        new Thread(() -> {
            shutdownConnectionAndHandler();

            setBluetoothServiceState(BluetoothServiceState.SERVICE_STOPPED);

            mLocationHandler.stopLocating();

            if (mCommandListener != null) {
                mCommandListener.shutdown();
            }

            Notification noti = new NotificationCompat.Builder(getApplicationContext())
                    .setContentTitle("enviroCar")
                    .setContentText(getResources()
                            .getText(R.string.service_state_stopped))
                    .setSmallIcon(R.drawable.dashboard).setAutoCancel(true).build();

            NotificationManager manager = (NotificationManager) getSystemService(Context
                    .NOTIFICATION_SERVICE);
            manager.notify(BG_NOTIFICATION_ID, noti);

            doTextToSpeech("Device disconnected");

            // Set state of the remoteService to stopped.
            setBluetoothServiceState(BluetoothServiceState.SERVICE_STOPPED);
        }).start();
    }

    private void onDeviceConnected(BluetoothSocketWrapper bluetoothSocket) {
        try {
            InputStream in = bluetoothSocket.getInputStream();
            OutputStream out = bluetoothSocket.getOutputStream();

            if (mCommandListener != null) {
                mCommandListener.shutdown();
            }

            this.mCommandListener = new CommandListener(getApplicationContext());
            this.mOBDCommandLooper = new OBDCommandLooper(in, out, bluetoothSocket
                    .getRemoteDeviceName(), this.mCommandListener, new ConnectionListener() {

                private int mReconnectCount = 0;

                @Override
                public void onConnectionVerified() {
                    setBluetoothServiceState(BluetoothServiceState.SERVICE_STARTED);
                }

                @Override
                public void onAllAdaptersFailed() {
                    LOG.info("all adapters failed!");
                    stopOBDConnection();
                    doTextToSpeech("failed to connect to the OBD adapter");
                    //                  sendBroadcast(new Intent
                    // (CONNECTION_PERMANENTLY_FAILED_INTENT));
                }

                @Override
                public void onStatusUpdate(String message) {

                }

                @Override
                public void requestConnectionRetry(IOException e) {
                    if (mReconnectCount++ >= MAX_RECONNECT_COUNT) {
                        LOG.warn("Max count of reconnecctes reaced", e);
                    } else {
                        LOG.info("Restarting Device Connection...");
                        doTextToSpeech("Connection lost. Trying to reconnect.");
                    }
                }
            });
            this.mOBDCommandLooper.start();
        } catch (IOException e) {
            LOG.warn(e.getMessage(), e);
            deviceDisconnected();
            return;
        }

        doTextToSpeech("Connection established");
    }

    public void deviceDisconnected() {
        LOG.info("Bluetooth device disconnected.");
        stopOBDConnection();
    }

    private void shutdownConnectionAndHandler() {
        if (mOBDCommandLooper != null) {
            mOBDCommandLooper.stopLooper();
        }

        if (mOBDConnection != null) {
            mOBDConnection.cancelConnection();
        }
    }


    /**
     *
     */
    class OBDBluetoothConnection extends Thread {

        // The required input variables.
        private final BluetoothDevice mDevice;
        private final List<UUID> mUUIDCandidates;

        // Boolean variables indicating the state.
        private boolean mSuccess;
        private boolean mIsRunning;

        // The socket wrapper for the connection.
        private BluetoothSocketWrapper mSocketWrapper;

        /**
         * Constructor
         *
         * @param device
         * @param uuids
         */
        public OBDBluetoothConnection(BluetoothDevice device, List<UUID> uuids) {
            this.mDevice = device;
            this.mUUIDCandidates = uuids;
            this.mIsRunning = true;
        }

        @Override
        public void run() {
            for (UUID uuid : mUUIDCandidates) {
                if (!mIsRunning)
                    return;

                try {
                    LOG.info("Trying to create native bleutooth socket");
                    mSocketWrapper = new NativeBluetoothSocket(mDevice
                            .createRfcommSocketToServiceRecord(uuid));
                } catch (IOException e) {
                    LOG.info("Error");

                    LOG.warn(e.getMessage(), e);
                    continue;
                }

                if (mSocketWrapper == null)
                    continue;

                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    mSocketWrapper.connect();
                    mSuccess = true;
                } catch (IOException e) {
                    LOG.warn("Exception on bluetooth connection. Trying " +
                            "the fallback... : "
                            + e.getMessage(), e);
                    try {
                        //try the fallback
                        if (mIsRunning) {

                            mSocketWrapper = new FallbackBluetoothSocket(mSocketWrapper
                                    .getUnderlyingSocket());
                            Thread.sleep(500);
                            mSocketWrapper.connect();
                            mSuccess = true;
                        }
                    } catch (FallbackBluetoothSocket.FallbackException e1) {
                        e1.printStackTrace();
                    } catch (InterruptedException e1) {
                        e1.printStackTrace();
                    } catch (IOException e1) {
                        shutdownSocket(mSocketWrapper);
                    }
                }

                if (mSuccess) {
                    LOG.info("successful connected");
                    onDeviceConnected(mSocketWrapper);
                    break;
                }
            }
        }

        private void shutdownSocket(BluetoothSocketWrapper socket) {
            OBDConnectionService.LOG.info("Shutting down bluetooth socket.");

            try {
                if (socket.getInputStream() != null)
                    socket.getInputStream().close();

            } catch (Exception e) {
            }

            try {
                if (socket.getOutputStream() != null)
                    socket.getOutputStream().close();

            } catch (Exception e) {
            }

            try {
                socket.close();
            } catch (Exception e) {
            }
        }

        private void cancelConnection() {
            mIsRunning = false;
            shutdownSocket(mSocketWrapper);
        }
    }

    public class OBDConnectionRecognizer {
        private static final long OBD_INTERVAL = 1000 * 10; // 10 seconds;
        private static final long GPS_INTERVAL = 1000 * 60 * 2; // 2 minutes;

        private long timeLastSpeedMeasurement;
        private long timeLastGpsMeasurement;

        private final Scheduler.Worker mBackgroundWorker = Schedulers.io().createWorker();
        private Subscription mOBDCheckerSubscription;
        private Subscription mGPSCheckerSubscription;

        private final Action0 gpsConnectionCloser = () -> {
            LOG.warn("CONNECTION CLOSED due to no GPS values");
            stopOBDConnection();
        };

        private final Action0 obdConnectionCloser = () -> {
            LOG.warn("CONNECTION CLOSED due to no OBD values");
            stopOBDConnection();
        };

        /**
         * Constructor.
         */
        public OBDConnectionRecognizer() {

        }

        @Subscribe
        public void onReceiveGpsLocationChangedEvent(GpsLocationChangedEvent event) {
            if (mGPSCheckerSubscription != null) {
                mGPSCheckerSubscription.unsubscribe();
                mGPSCheckerSubscription = null;
            }

            timeLastGpsMeasurement = System.currentTimeMillis();

            mGPSCheckerSubscription = mBackgroundWorker.schedule(
                    gpsConnectionCloser, GPS_INTERVAL, TimeUnit.MILLISECONDS);
        }

        @Subscribe
        public void onReceiveSpeedUpdateEvent(SpeedUpdateEvent event) {
            if (mOBDCheckerSubscription != null) {
                mOBDCheckerSubscription.unsubscribe();
                mOBDCheckerSubscription = null;
            }

            timeLastSpeedMeasurement = System.currentTimeMillis();

            mOBDCheckerSubscription = mBackgroundWorker.schedule(
                    obdConnectionCloser, OBD_INTERVAL, TimeUnit.MILLISECONDS);
        }
    }


    /**
     * Class used for the client Binder. The remoteService is running in the same process as its
     * client, so it is not required to deal with IPC.
     */
    public class OBDConnectionBinder extends Binder {

        /**
         * Returns the instance of the enclosing remoteService.
         *
         * @return the enclosing remoteService.
         */
        public OBDConnectionService getService() {
            return OBDConnectionService.this;
        }
    }
}