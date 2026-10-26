package dev.foxx.xiaomirc.hid;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;

/**
 * Classic Bluetooth HID device. Android TV's Accessories screen scans inquiry
 * results and keeps only peripherals (keyboard, remote, gamepad). A BLE advert
 * with no Appearance field is classified as an unclassified peripheral and
 * dropped, which is why the TV sat on "searching" while a manual phone-side
 * pair still created a BR/EDR bond and never opened an HID channel.
 */
public final class ClassicHidSession {
    private static final String TAG = "XiaomiRc";

    public interface Host {
        void onAdvertising();

        void onBonding(BluetoothDevice device);

        void onBonded(BluetoothDevice device);

        void onConnected(BluetoothDevice device);

        void onReady(BluetoothDevice device);

        void onDisconnected();

        void onFailed(String message);
    }

    private final Context context;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor executor = command -> main.post(command);
    private final byte[] descriptor = HidReportMap.build();

    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice connected;
    private BluetoothDevice pendingHost;
    private boolean registered;
    private boolean closed;
    private boolean receiverRegistered;
    private boolean bondReset;
    private boolean linkInProgress;
    private int hidFailures;
    private byte[] lastConsumer = HidReports.consumer(0);
    private byte[] lastKeyboard = HidReports.keyboard(0);

    public ClassicHidSession(Context context, Host host) {
        this.context = context.getApplicationContext();
        this.host = host;
    }

    public void open() {
        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            host.onFailed("Bluetooth is not available on this phone");
            return;
        }
        if (!adapter.isEnabled()) {
            host.onFailed("Turn Bluetooth on, then try again");
            return;
        }
        markAsKeyboard(adapter);
        registerReceiver();
        boolean proxy = adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE);
        if (!proxy) {
            host.onFailed("This phone has no Bluetooth HID device profile");
        }
    }

    public void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        unregisterReceiver();
        if (adapter != null) {
            try {
                adapter.cancelDiscovery();
            } catch (SecurityException ignored) {
                // Closing anyway.
            }
        }
        BluetoothHidDevice device = hid;
        hid = null;
        registered = false;
        connected = null;
        if (device != null) {
            try {
                device.unregisterApp();
            } catch (SecurityException e) {
                Log.w(TAG, "unregister HID", e);
            }
            if (adapter != null) {
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, device);
            }
        }
    }

    public boolean canSend(RemoteKey key) {
        return hid != null && connected != null && registered;
    }

    public void press(RemoteKey key) {
        main.post(() -> send(key, true));
    }

    public void release(RemoteKey key) {
        main.post(() -> send(key, false));
    }

    /** Remember which TV to open. Discovery starts the classic pair; paging waits until it is seen. */
    public void remember(BluetoothDevice device) {
        pendingHost = device;
    }

    private void startSearch() {
        if (closed || adapter == null || connected != null || linkInProgress || !adapter.isEnabled()) {
            return;
        }
        if (adapter.isDiscovering()) {
            return;
        }
        try {
            boolean started = adapter.startDiscovery();
            Log.i(TAG, "discovery " + started);
            if (!started) {
                host.onFailed("Allow location access so this phone can find Family Room TV.");
            }
        } catch (SecurityException e) {
            Log.w(TAG, "startDiscovery", e);
            host.onFailed("Allow location access so this phone can find Family Room TV.");
        }
    }

    private void onFound(BluetoothDevice device) {
        if (device == null || connected != null || linkInProgress || !isTv(device)) {
            return;
        }
        String name = safeName(device);
        int bond = BluetoothDevice.BOND_NONE;
        try {
            bond = device.getBondState();
        } catch (SecurityException ignored) {
            // Fall through and try to bond.
        }
        Log.i(TAG, "found " + name + " " + device.getAddress() + " bond=" + bond);
        pendingHost = device;
        linkInProgress = true;
        try {
            adapter.cancelDiscovery();
        } catch (SecurityException ignored) {
            // The pair can still proceed.
        }
        if (bond == BluetoothDevice.BOND_BONDED) {
            ensureHid();
            return;
        }
        if (bond == BluetoothDevice.BOND_BONDING) {
            host.onBonding(device);
            return;
        }
        host.onBonding(device);
        try {
            boolean started = device.createBond();
            Log.i(TAG, "createBond " + device.getAddress() + " -> " + started);
            if (!started) {
                linkInProgress = false;
                main.postDelayed(() -> startSearch(), 1500);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "createBond", e);
            linkInProgress = false;
        }
    }

    private boolean isTv(BluetoothDevice device) {
        if (pendingHost != null && pendingHost.getAddress().equals(device.getAddress())) {
            return true;
        }
        String name = safeName(device);
        return name != null && name.equalsIgnoreCase("Family Room TV");
    }

    private String safeName(BluetoothDevice device) {
        try {
            return device.getName();
        } catch (SecurityException e) {
            return null;
        }
    }

    private void forgetStaleBond() {
        if (bondReset || pendingHost == null) {
            return;
        }
        int bond = BluetoothDevice.BOND_NONE;
        try {
            bond = pendingHost.getBondState();
        } catch (SecurityException e) {
            return;
        }
        if (bond != BluetoothDevice.BOND_BONDED) {
            return;
        }
        bondReset = true;
        try {
            Method method = BluetoothDevice.class.getMethod("removeBond");
            Object result = method.invoke(pendingHost);
            Log.i(TAG, "removeBond " + pendingHost.getAddress() + " -> " + result);
        } catch (Throwable t) {
            Log.w(TAG, "removeBond unavailable", t);
        }
    }

    private void registerReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterReceiver() {
        if (!receiverRegistered) {
            return;
        }
        receiverRegistered = false;
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered.
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (closed || intent.getAction() == null) {
                return;
            }
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                onFound(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Log.i(TAG, "discovery finished");
                if (connected == null && !linkInProgress) {
                    main.postDelayed(() -> startSearch(), 1000);
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                if (device == null || !isTv(device)) {
                    return;
                }
                Log.i(TAG, "bond " + state + " " + device.getAddress());
                pendingHost = device;
                if (state == BluetoothDevice.BOND_BONDING) {
                    linkInProgress = true;
                    host.onBonding(device);
                } else if (state == BluetoothDevice.BOND_BONDED) {
                    linkInProgress = true;
                    host.onConnected(device);
                    ensureHid();
                } else if (state == BluetoothDevice.BOND_NONE) {
                    linkInProgress = false;
                    host.onDisconnected();
                    main.postDelayed(() -> startSearch(), 4000);
                }
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null || !isTv(device)) {
                    return;
                }
                Log.i(TAG, "ACL up " + device.getAddress());
                pendingHost = device;
                ensureHid();
            }
        }
    };

    private void send(RemoteKey key, boolean down) {
        if (!canSend(key)) {
            return;
        }
        int id = key.kind == RemoteKey.Kind.KEYBOARD
                ? HidReports.KEYBOARD_REPORT_ID
                : HidReports.CONSUMER_REPORT_ID;
        byte[] payload = down ? HidReports.press(key) : HidReports.release(key);
        if (key.kind == RemoteKey.Kind.KEYBOARD) {
            if (down) {
                lastConsumer = HidReports.consumer(0);
            }
            lastKeyboard = payload;
        } else {
            if (down) {
                lastKeyboard = HidReports.keyboard(0);
            }
            lastConsumer = payload;
        }
        try {
            boolean ok = hid.sendReport(connected, id, payload);
            Log.i(TAG, "report " + key + (down ? " down " : " up ") + ok);
        } catch (SecurityException e) {
            Log.w(TAG, "sendReport", e);
        }
    }

    private boolean hidRequested;

    private void ensureHid() {
        if (closed || hid == null || connected != null) {
            return;
        }
        if (registered) {
            connectPending();
            return;
        }
        if (hidRequested) {
            return;
        }
        hidRequested = true;
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "XMRM-ML",
                "Xiaomi TV remote",
                "XMRM-ML",
                BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                descriptor);
        BluetoothHidDeviceAppQosSettings qos = new BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                800, 9, 0, 11250, 11250);
        try {
            boolean ok = hid.registerApp(sdp, qos, qos, executor, callback);
            Log.i(TAG, "registerApp " + ok);
            if (!ok) {
                hidRequested = false;
                host.onFailed("Could not register as a Bluetooth keyboard");
            }
        } catch (SecurityException e) {
            hidRequested = false;
            host.onFailed("Bluetooth permission was denied");
        }
    }

    private void connectPending() {
        if (closed || hid == null || !registered || pendingHost == null || connected != null) {
            return;
        }
        try {
            boolean started = hid.connect(pendingHost);
            Log.i(TAG, "HID connect " + pendingHost.getAddress() + " -> " + started);
            if (started) {
                host.onConnected(pendingHost);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "HID connect", e);
        }
    }

    private void markAsKeyboard(BluetoothAdapter bluetooth) {
        logClass("before", bluetooth);
        try {
            java.lang.reflect.Constructor<BluetoothClass> ctor =
                    BluetoothClass.class.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            BluetoothClass keyboard = ctor.newInstance(0x2540);
            Method method = BluetoothAdapter.class.getMethod("setBluetoothClass", BluetoothClass.class);
            Object result = method.invoke(bluetooth, keyboard);
            Log.i(TAG, "setBluetoothClass -> " + result);
        } catch (Throwable t) {
            Log.w(TAG, "setBluetoothClass unavailable", t);
        }
        try {
            Method scan = BluetoothAdapter.class.getMethod("setScanMode", int.class, int.class);
            Object result = scan.invoke(bluetooth, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, 300);
            Log.i(TAG, "setScanMode discoverable -> " + result);
        } catch (Throwable t) {
            Log.w(TAG, "setScanMode unavailable", t);
        }
        logClass("after", bluetooth);
    }

    private void logClass(String when, BluetoothAdapter bluetooth) {
        try {
            Method method = BluetoothAdapter.class.getMethod("getBluetoothClass");
            BluetoothClass current = (BluetoothClass) method.invoke(bluetooth);
            if (current == null) {
                Log.i(TAG, "class " + when + " null");
                return;
            }
            Log.i(TAG, "class " + when + " major=" + current.getMajorDeviceClass()
                    + " device=" + current.getDeviceClass());
        } catch (Throwable t) {
            Log.w(TAG, "getBluetoothClass " + when, t);
        }
    }

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (closed || profile != BluetoothProfile.HID_DEVICE) {
                return;
            }
            hid = (BluetoothHidDevice) proxy;
            host.onAdvertising();
            startSearch();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hid = null;
                registered = false;
                if (!closed) {
                    host.onFailed("Bluetooth HID profile disconnected");
                }
            }
        }
    };

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registeredNow) {
            registered = registeredNow;
            Log.i(TAG, "HID app registered=" + registeredNow);
            if (!registeredNow || closed) {
                return;
            }
            connectPending();
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            Log.i(TAG, "HID state " + state + " " + (device == null ? "" : device.getAddress()));
            if (state == BluetoothProfile.STATE_CONNECTED && device != null) {
                connected = device;
                pendingHost = device;
                linkInProgress = false;
                hidFailures = 0;
                host.onBonded(device);
                host.onReady(device);
            } else if (state == BluetoothProfile.STATE_CONNECTING && device != null) {
                linkInProgress = true;
                host.onConnected(device);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                connected = null;
                linkInProgress = false;
                hidFailures++;
                host.onDisconnected();
                main.postDelayed(() -> startSearch(), 1500);
            }
        }

        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            if (hid == null) {
                return;
            }
            byte[] data = id == HidReports.KEYBOARD_REPORT_ID ? lastKeyboard : lastConsumer;
            if (bufferSize > 0 && bufferSize < data.length) {
                byte[] trimmed = new byte[bufferSize];
                System.arraycopy(data, 0, trimmed, 0, bufferSize);
                data = trimmed;
            }
            try {
                hid.replyReport(device, type, id, data);
            } catch (SecurityException e) {
                Log.w(TAG, "replyReport", e);
            }
        }

        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            if (hid == null) {
                return;
            }
            try {
                hid.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS);
            } catch (SecurityException e) {
                Log.w(TAG, "reportError", e);
            }
        }

        @Override
        public void onSetProtocol(BluetoothDevice device, byte protocol) {
            Log.i(TAG, "protocol " + protocol);
        }

        @Override
        public void onInterruptData(BluetoothDevice device, byte reportId, byte[] data) {
            // Host output reports (keyboard LEDs). Nothing to show on this remote.
        }

        @Override
        public void onVirtualCableUnplug(BluetoothDevice device) {
            connected = null;
            host.onDisconnected();
        }
    };
}
