package com.zxcpoiu.incallmanager.AppRTC;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.List;
import com.zxcpoiu.incallmanager.AppRTC.AppRTCUtils;
import com.zxcpoiu.incallmanager.AppRTC.ThreadUtils;
import com.zxcpoiu.incallmanager.InCallManagerModule;

/**
 * Refactored AppRTCBluetoothManager to minimize reliance on BLUETOOTH_CONNECT permission.
 */
public class AppRTCBluetoothManager {
    private static final String TAG = "AppRTCBluetoothManager";
    private static final int BLUETOOTH_SCO_TIMEOUT_MS = 4000;

    public enum State {
        UNINITIALIZED,
        ERROR,
        HEADSET_UNAVAILABLE,
        HEADSET_AVAILABLE,
        SCO_DISCONNECTING,
        SCO_CONNECTING,
        SCO_CONNECTED
    }

    private final Context apprtcContext;
    private final InCallManagerModule apprtcAudioManager;
    @Nullable
    private final AudioManager audioManager;
    private final Handler handler;

    private State bluetoothState;
    private int scoConnectionAttempts;

    @Nullable
    private AudioDeviceInfo bluetoothAudioDevice;

    private final AudioDeviceCallback bluetoothAudioDeviceCallback;

    private final Runnable bluetoothTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            bluetoothTimeout();
        }
    };

    public static AppRTCBluetoothManager create(Context context, InCallManagerModule audioManager) {
        Log.d(TAG, "create");
        return new AppRTCBluetoothManager(context, audioManager);
    }

    protected AppRTCBluetoothManager(Context context, InCallManagerModule audioManager) {
        Log.d(TAG, "ctor");
        this.apprtcContext = context;
        this.apprtcAudioManager = audioManager;
        this.audioManager = getAudioManager(context);
        this.bluetoothState = State.UNINITIALIZED;
        this.handler = new Handler(Looper.getMainLooper());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            this.bluetoothAudioDeviceCallback = new BluetoothAudioDeviceCallback();
        } else {
            this.bluetoothAudioDeviceCallback = null;
        }
    }

    public State getState() {
        return bluetoothState;
    }

    public void start() {
        Log.d(TAG, "start");
        if (bluetoothState != State.UNINITIALIZED) {
            Log.w(TAG, "Bluetooth already initialized.");
            return;
        }

        if (!isBluetoothSupported()) {
            Log.w(TAG, "Device does not support Bluetooth.");
            bluetoothState = State.ERROR;
            return;
        }

        if (!isBluetoothEnabled()) {
            Log.w(TAG, "Bluetooth is not enabled.");
            bluetoothState = State.ERROR;
            return;
        }

        if (!isBluetoothScoAvailable()) {
            Log.w(TAG, "Bluetooth SCO audio is not available off call.");
            bluetoothState = State.ERROR;
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.registerAudioDeviceCallback(bluetoothAudioDeviceCallback, handler);
        }

        bluetoothState = State.HEADSET_UNAVAILABLE;
        Log.d(TAG, "Bluetooth initialized. State: " + bluetoothState);
    }

    public void stop() {
        Log.d(TAG, "stop");
        if (bluetoothState == State.UNINITIALIZED) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.unregisterAudioDeviceCallback(bluetoothAudioDeviceCallback);
        }

        cancelTimer();
        bluetoothState = State.UNINITIALIZED;
        Log.d(TAG, "Bluetooth stopped. State: " + bluetoothState);
    }

    public void updateDevice() {
        Log.d(TAG, "updateDevice");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothAudioDevice = getScoDevice();
            bluetoothState = (bluetoothAudioDevice != null) ? State.HEADSET_AVAILABLE : State.HEADSET_UNAVAILABLE;
        } else {
            Log.w(TAG, "updateDevice not supported on this Android version.");
            bluetoothState = State.UNINITIALIZED;
        }
    }

    public boolean startScoAudio() {
        Log.d(TAG, "startScoAudio: " + bluetoothState);
        if (bluetoothState != State.HEADSET_AVAILABLE) {
            Log.w(TAG, "No available Bluetooth headset.");
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (bluetoothAudioDevice != null) {
                audioManager.setCommunicationDevice(bluetoothAudioDevice);
                bluetoothState = State.SCO_CONNECTED;
                Log.d(TAG, "Bluetooth audio SCO connected.");
            } else {
                bluetoothState = State.SCO_DISCONNECTING;
                Log.w(TAG, "No Bluetooth SCO device found.");
            }
        } else {
            Log.d(TAG, "Starting Bluetooth SCO...");
            bluetoothState = State.SCO_CONNECTING;
            startTimer();
            audioManager.startBluetoothSco();
            audioManager.setBluetoothScoOn(true);
        }
        return true;
    }

    public void stopScoAudio() {
        Log.d(TAG, "stopScoAudio: " + bluetoothState);
        if (bluetoothState != State.SCO_CONNECTING && bluetoothState != State.SCO_CONNECTED) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice();
        } else {
            audioManager.stopBluetoothSco();
            audioManager.setBluetoothScoOn(false);
        }

        bluetoothState = State.SCO_DISCONNECTING;
        Log.d(TAG, "Bluetooth audio SCO disconnected.");
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private class BluetoothAudioDeviceCallback extends AudioDeviceCallback {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            updateDeviceList();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            updateDeviceList();
        }

        private void updateDeviceList() {
            final AudioDeviceInfo newBtDevice = getScoDevice();
            if (newBtDevice != null) {
                bluetoothAudioDevice = newBtDevice;
                bluetoothState = State.HEADSET_AVAILABLE;
            } else {
                bluetoothState = State.HEADSET_UNAVAILABLE;
            }
            Log.d(TAG, "Bluetooth device list updated. State: " + bluetoothState);
        }
    }

    @Nullable
    @RequiresApi(api = Build.VERSION_CODES.S)
    private AudioDeviceInfo getScoDevice() {
        if (audioManager != null) {
            List<AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
            for (AudioDeviceInfo device : devices) {
                if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    return device;
                }
            }
        }
        return null;
    }

    private void bluetoothTimeout() {
        Log.w(TAG, "Bluetooth SCO connection timed out.");
        stopScoAudio();
    }

    private void startTimer() {
        handler.postDelayed(bluetoothTimeoutRunnable, BLUETOOTH_SCO_TIMEOUT_MS);
    }

    private void cancelTimer() {
        handler.removeCallbacks(bluetoothTimeoutRunnable);
    }

    private boolean isBluetoothSupported() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null;
    }

    private boolean isBluetoothEnabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }

    private boolean isBluetoothScoAvailable() {
        return audioManager != null && audioManager.isBluetoothScoAvailableOffCall();
    }

    private AudioManager getAudioManager(Context context) {
        return (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }
}