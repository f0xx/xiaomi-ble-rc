package dev.foxx.xiaomirc.hid;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BLE peripheral that speaks HID-over-GATT. The TV is the central: it scans,
 * bonds over LE, and subscribes to input reports. This phone never scans.
 */
public final class HidPeripheral {
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

    private static final UUID HID_SERVICE = uuid16(0x1812);
    private static final UUID DIS_SERVICE = uuid16(0x180A);
    private static final UUID BATTERY_SERVICE = uuid16(0x180F);
    private static final UUID HID_INFORMATION = uuid16(0x2A4A);
    private static final UUID REPORT_MAP = uuid16(0x2A4B);
    private static final UUID HID_CONTROL_POINT = uuid16(0x2A4C);
    private static final UUID REPORT = uuid16(0x2A4D);
    private static final UUID PROTOCOL_MODE = uuid16(0x2A4E);
    private static final UUID MANUFACTURER_NAME = uuid16(0x2A29);
    private static final UUID MODEL_NUMBER = uuid16(0x2A24);
    private static final UUID PNP_ID = uuid16(0x2A50);
    private static final UUID BATTERY_LEVEL = uuid16(0x2A19);
    private static final UUID REPORT_REFERENCE = uuid16(0x2908);
    private static final UUID CCCD = uuid16(0x2902);

    /** RemoteWake | NormallyConnectable, so a bonded TV can wake from standby. */
    private static final byte[] HID_INFO = {0x11, 0x01, 0x00, 0x03};
    /** USB vendor source, Xiaomi VID 0x2717, product 0x32B9 (stock remote key layout). */
    private static final byte[] PNP = {0x02, 0x17, 0x27, (byte) 0xB9, 0x32, 0x00, 0x00};

    private static final int READ = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED;
    private static final int WRITE = BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED;

    private final Context context;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final byte[] reportMap = HidReportMap.build();
    private final Map<BluetoothGattCharacteristic, byte[]> values = new IdentityHashMap<>();
    private final ArrayDeque<BluetoothGattService> pendingServices = new ArrayDeque<>();
    private final ArrayDeque<Outgoing> outgoing = new ArrayDeque<>();
    private final Set<BluetoothDevice> links = new HashSet<>();
    private final Set<BluetoothDevice> consumerSubs = new HashSet<>();
    private final Set<BluetoothDevice> keyboardSubs = new HashSet<>();
    private final Map<String, Integer> mtus = new HashMap<>();

    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattCharacteristic consumerInput;
    private BluetoothGattCharacteristic keyboardInput;
    private BluetoothGattCharacteristic keyboardOutput;
    private BluetoothGattCharacteristic protocolMode;
    private boolean servicesReady;
    private boolean advertising;
    private boolean closed;
    private boolean suspended;
    private boolean readyNotified;
    private int advertiseAttempt;
    private int pendingNotifies;
    private int epoch;
    private RemoteKey heldKeyboard;
    private RemoteKey heldConsumer;

    public HidPeripheral(Context context, Host host) {
        this.context = context.getApplicationContext();
        this.host = host;
    }

    public void open() {
        if (closed) {
            return;
        }
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager == null) {
            fail("Bluetooth is not available on this phone");
            return;
        }
        BluetoothAdapter adapter = manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            fail("Turn Bluetooth on, then try again");
            return;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null || !adapter.isMultipleAdvertisementSupported()) {
            fail("This phone cannot advertise as a Bluetooth LE accessory");
            return;
        }
        gattServer = manager.openGattServer(context, gattCallback);
        if (gattServer == null) {
            fail("Could not open a BLE GATT server");
            return;
        }
        registerBondReceiver();
        enqueueServices();
        main.post(this::pumpServices);
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        main.removeCallbacksAndMessages(null);
        unregisterBondReceiver();
        stopAdvertising();
        BluetoothGattServer server = gattServer;
        gattServer = null;
        if (server != null) {
            for (BluetoothDevice device : new HashSet<>(links)) {
                try {
                    server.cancelConnection(device);
                } catch (RuntimeException ignored) {
                    // Adapter may already be off.
                }
            }
            try {
                server.close();
            } catch (RuntimeException ignored) {
                // Adapter may already be off.
            }
        }
        links.clear();
        consumerSubs.clear();
        keyboardSubs.clear();
    }

    public boolean canSend(RemoteKey key) {
        if (suspended && key != RemoteKey.POWER) {
            return false;
        }
        return key.kind == RemoteKey.Kind.KEYBOARD ? !keyboardSubs.isEmpty() : !consumerSubs.isEmpty();
    }

    public void press(RemoteKey key) {
        main.post(() -> {
            if (closed || !canSend(key)) {
                return;
            }
            if (key.kind == RemoteKey.Kind.KEYBOARD) {
                if (heldConsumer != null) {
                    enqueue(heldConsumer, false);
                    heldConsumer = null;
                }
                heldKeyboard = key;
            } else {
                if (heldKeyboard != null) {
                    enqueue(heldKeyboard, false);
                    heldKeyboard = null;
                }
                heldConsumer = key;
            }
            enqueue(key, true);
        });
    }

    public void release(RemoteKey key) {
        main.post(() -> {
            if (closed) {
                return;
            }
            if (key.kind == RemoteKey.Kind.KEYBOARD) {
                if (heldKeyboard == key) {
                    heldKeyboard = null;
                }
            } else if (heldConsumer == key) {
                heldConsumer = null;
            }
            if (canSend(key) || key == RemoteKey.POWER) {
                enqueue(key, false);
            }
        });
    }

    /** Ask the stack to reconnect a TV we already bonded, in case it is in standby. */
    public void remember(BluetoothDevice device) {
        main.post(() -> {
            if (closed || gattServer == null || device == null) {
                return;
            }
            try {
                if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    gattServer.connect(device, true);
                }
            } catch (SecurityException e) {
                Log.w(TAG, "reconnect failed", e);
            }
        });
    }

    private void enqueue(RemoteKey key, boolean down) {
        byte[] payload = down ? HidReports.press(key) : HidReports.release(key);
        BluetoothGattCharacteristic characteristic =
                key.kind == RemoteKey.Kind.KEYBOARD ? keyboardInput : consumerInput;
        if (characteristic == null) {
            return;
        }
        synchronized (values) {
            values.put(characteristic, payload);
        }
        synchronized (outgoing) {
            outgoing.add(new Outgoing(characteristic, payload));
        }
        if (pendingNotifies == 0) {
            pumpOutgoing();
        }
    }

    private void pumpOutgoing() {
        if (closed || pendingNotifies > 0 || gattServer == null) {
            return;
        }
        Outgoing next;
        synchronized (outgoing) {
            next = outgoing.poll();
        }
        if (next == null) {
            return;
        }
        Set<BluetoothDevice> targets = subsFor(next.characteristic);
        int sent = 0;
        for (BluetoothDevice device : targets) {
            if (notifyOne(device, next.characteristic, next.value)) {
                sent++;
            }
        }
        if (sent == 0) {
            pumpOutgoing();
            return;
        }
        pendingNotifies = sent;
        int epochAtStart = epoch;
        main.postDelayed(() -> finishBatch(epochAtStart), 250);
    }

    private void finishBatch(int epochAtStart) {
        if (epochAtStart != epoch) {
            return;
        }
        epoch++;
        pendingNotifies = 0;
        pumpOutgoing();
    }

    private boolean notifyOne(BluetoothDevice device, BluetoothGattCharacteristic characteristic, byte[] value) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return notifyApi33(device, characteristic, value);
            }
            characteristic.setValue(value);
            return gattServer.notifyCharacteristicChanged(device, characteristic, false);
        } catch (SecurityException e) {
            Log.w(TAG, "notify denied", e);
            return false;
        }
    }

    private boolean notifyApi33(BluetoothDevice device, BluetoothGattCharacteristic characteristic, byte[] value) {
        int code = gattServer.notifyCharacteristicChanged(device, characteristic, false, value);
        return code == android.bluetooth.BluetoothStatusCodes.SUCCESS;
    }

    private Set<BluetoothDevice> subsFor(BluetoothGattCharacteristic characteristic) {
        if (characteristic == keyboardInput) {
            return new HashSet<>(keyboardSubs);
        }
        if (characteristic == consumerInput) {
            return new HashSet<>(consumerSubs);
        }
        return new HashSet<>();
    }

    private void enqueueServices() {
        pendingServices.add(hidService());
        pendingServices.add(batteryService());
        pendingServices.add(deviceInfoService());
    }

    private void pumpServices() {
        if (closed || gattServer == null) {
            return;
        }
        BluetoothGattService next = pendingServices.poll();
        if (next == null) {
            servicesReady = true;
            advertiseAttempt = 0;
            advertise();
            return;
        }
        try {
            if (!gattServer.addService(next)) {
                fail("Could not add the HID service");
            }
        } catch (SecurityException e) {
            fail("Bluetooth permission was denied");
        }
    }

    private BluetoothGattService hidService() {
        BluetoothGattService service = new BluetoothGattService(
                HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        addRead(service, HID_INFORMATION, HID_INFO);
        addRead(service, REPORT_MAP, reportMap);

        protocolMode = readWrite(PROTOCOL_MODE, new byte[]{0x01});
        service.addCharacteristic(protocolMode);

        BluetoothGattCharacteristic control = new BluetoothGattCharacteristic(
                HID_CONTROL_POINT,
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                WRITE);
        service.addCharacteristic(control);

        consumerInput = inputReport(new byte[]{0x01, 0x01});
        keyboardInput = inputReport(new byte[]{0x02, 0x01});
        service.addCharacteristic(consumerInput);
        service.addCharacteristic(keyboardInput);

        keyboardOutput = new BluetoothGattCharacteristic(
                REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ
                        | BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                READ | WRITE);
        keyboardOutput.addDescriptor(reportReference(new byte[]{0x02, 0x02}));
        values.put(keyboardOutput, new byte[]{0x00});
        service.addCharacteristic(keyboardOutput);
        return service;
    }

    private BluetoothGattService batteryService() {
        BluetoothGattService service = new BluetoothGattService(
                BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic level = new BluetoothGattCharacteristic(
                BATTERY_LEVEL,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                READ);
        level.setValue(new byte[]{100});
        values.put(level, new byte[]{100});
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(CCCD, READ | WRITE);
        cccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        level.addDescriptor(cccd);
        service.addCharacteristic(level);
        return service;
    }

    private BluetoothGattService deviceInfoService() {
        BluetoothGattService service = new BluetoothGattService(
                DIS_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        addRead(service, MANUFACTURER_NAME, "Xiaomi".getBytes(StandardCharsets.UTF_8));
        addRead(service, MODEL_NUMBER, "XMRM-ML".getBytes(StandardCharsets.UTF_8));
        addRead(service, PNP_ID, PNP);
        return service;
    }

    private void addRead(BluetoothGattService service, UUID uuid, byte[] value) {
        service.addCharacteristic(readOnly(uuid, value));
    }

    private BluetoothGattCharacteristic readOnly(UUID uuid, byte[] value) {
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                uuid, BluetoothGattCharacteristic.PROPERTY_READ, READ);
        characteristic.setValue(value);
        values.put(characteristic, value);
        return characteristic;
    }

    private BluetoothGattCharacteristic readWrite(UUID uuid, byte[] value) {
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                uuid,
                BluetoothGattCharacteristic.PROPERTY_READ
                        | BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                READ | WRITE);
        characteristic.setValue(value);
        values.put(characteristic, value);
        return characteristic;
    }

    private BluetoothGattCharacteristic inputReport(byte[] reference) {
        int length = reference[0] == HidReports.CONSUMER_REPORT_ID
                ? HidReports.CONSUMER_REPORT_LEN
                : HidReports.KEYBOARD_REPORT_LEN;
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                READ);
        byte[] initial = new byte[length];
        characteristic.setValue(initial);
        values.put(characteristic, initial);
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(CCCD, READ | WRITE);
        cccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        characteristic.addDescriptor(cccd);
        characteristic.addDescriptor(reportReference(reference));
        return characteristic;
    }

    private static BluetoothGattDescriptor reportReference(byte[] reference) {
        BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(REPORT_REFERENCE, READ);
        descriptor.setValue(reference);
        return descriptor;
    }

    private void advertise() {
        if (closed || advertiser == null || !servicesReady) {
            return;
        }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        AdvertiseData hid = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(new ParcelUuid(HID_SERVICE))
                .build();
        AdvertiseData named = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(HID_SERVICE))
                .build();
        try {
            if (advertiseAttempt == 0) {
                AdvertiseData scan = new AdvertiseData.Builder()
                        .setIncludeDeviceName(true)
                        .build();
                advertiser.startAdvertising(settings, hid, scan, advertiseCallback);
            } else if (advertiseAttempt == 1) {
                advertiser.startAdvertising(settings, named, advertiseCallback);
            } else {
                advertiser.startAdvertising(settings, hid, advertiseCallback);
            }
        } catch (SecurityException e) {
            fail("Bluetooth advertise permission was denied");
        } catch (IllegalStateException e) {
            fail("Bluetooth is off");
        }
    }

    private void stopAdvertising() {
        advertising = false;
        if (advertiser == null) {
            return;
        }
        try {
            advertiser.stopAdvertising(advertiseCallback);
        } catch (RuntimeException ignored) {
            // Adapter is off, or advertising never started.
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true;
            Log.i(TAG, "advertising as HID");
            main.post(host::onAdvertising);
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.w(TAG, "advertise failed: " + errorCode);
            if (closed) {
                return;
            }
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                return;
            }
            if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && advertiseAttempt < 2) {
                advertiseAttempt++;
                main.post(HidPeripheral.this::advertise);
                return;
            }
            fail(advertiseError(errorCode));
        }
    };

    private static String advertiseError(int errorCode) {
        switch (errorCode) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                return "The BLE advert is too large for this phone";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                return "Too many Bluetooth advertisers are already running";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                return "This phone cannot be a BLE HID peripheral";
            default:
                return "Bluetooth advertising failed (" + errorCode + ")";
        }
    }

    /**
     * {@code createBond(int)} is hidden. Reflection asks for LE only; the public
     * no-arg call is the fallback and still bonds the link the TV opened.
     */
    private static void createLeBond(BluetoothDevice device) throws SecurityException {
        try {
            java.lang.reflect.Method method = BluetoothDevice.class.getMethod("createBond", int.class);
            Object result = method.invoke(device, 2);
            if (Boolean.TRUE.equals(result)) {
                return;
            }
            Log.w(TAG, "createBond(LE) returned " + result + ", trying default bond");
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "createBond(LE) unavailable", e);
        }
        device.createBond();
    }

    private void maybeBond(BluetoothDevice device) {
        if (closed || device == null) {
            return;
        }
        try {
            int state = device.getBondState();
            if (state == BluetoothDevice.BOND_NONE) {
                host.onBonding(device);
                createLeBond(device);
            } else if (state == BluetoothDevice.BOND_BONDED && gattServer != null) {
                gattServer.connect(device, true);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "bond failed", e);
        }
    }

    private void onSubscribed(BluetoothDevice device) {
        if (readyNotified) {
            return;
        }
        readyNotified = true;
        host.onReady(device);
    }

    private void fail(String message) {
        Log.e(TAG, message);
        main.post(() -> host.onFailed(message));
    }

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            if (closed) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("HID service was rejected (" + status + ")");
                return;
            }
            Log.i(TAG, "service added " + service.getUuid());
            main.post(HidPeripheral.this::pumpServices);
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            main.post(() -> {
                if (closed || device == null) {
                    return;
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    links.add(device);
                    Log.i(TAG, "link up " + address(device) + " bond=" + safeBond(device));
                    host.onConnected(device);
                    main.postDelayed(() -> maybeBond(device), 500);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    links.remove(device);
                    consumerSubs.remove(device);
                    keyboardSubs.remove(device);
                    mtus.remove(address(device));
                    Log.i(TAG, "link down " + address(device));
                    if (links.isEmpty()) {
                        readyNotified = false;
                        heldKeyboard = null;
                        heldConsumer = null;
                        host.onDisconnected();
                        if (!closed && servicesReady) {
                            stopAdvertising();
                            advertiseAttempt = 0;
                            main.postDelayed(HidPeripheral.this::advertise, 400);
                        }
                    }
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            if (device != null && mtu > 23) {
                mtus.put(address(device), mtu);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                 BluetoothGattCharacteristic characteristic) {
            byte[] value = valueFor(characteristic);
            if (value == null && REPORT_MAP.equals(characteristic.getUuid())) {
                value = reportMap;
            }
            if (value == null) {
                respond(device, requestId, BluetoothGatt.GATT_FAILURE, offset, new byte[0]);
                return;
            }
            respondSlice(device, requestId, offset, value);
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                             BluetoothGattDescriptor descriptor) {
            byte[] value = descriptorValue(device, descriptor);
            if (value == null) {
                respond(device, requestId, BluetoothGatt.GATT_FAILURE, offset, new byte[0]);
                return;
            }
            respondSlice(device, requestId, offset, value);
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                  BluetoothGattCharacteristic characteristic,
                                                  boolean preparedWrite, boolean responseNeeded,
                                                  int offset, byte[] value) {
            BluetoothGattCharacteristic ours = match(characteristic);
            if (ours == protocolMode && value != null && value.length > 0) {
                synchronized (values) {
                    values.put(protocolMode, Arrays.copyOf(value, value.length));
                }
            } else if (ours == keyboardOutput && value != null) {
                synchronized (values) {
                    values.put(keyboardOutput, Arrays.copyOf(value, value.length));
                }
            } else if (HID_CONTROL_POINT.equals(characteristic.getUuid()) && value != null && value.length > 0) {
                suspended = value[0] == 0x00;
                Log.i(TAG, suspended ? "host suspended input" : "host resumed input");
            }
            if (responseNeeded) {
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, new byte[0]);
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                              BluetoothGattDescriptor descriptor,
                                              boolean preparedWrite, boolean responseNeeded,
                                              int offset, byte[] value) {
            if (CCCD.equals(descriptor.getUuid()) && device != null) {
                boolean enable = value != null && value.length > 0 && (value[0] & 0x01) != 0;
                BluetoothGattCharacteristic parent = descriptor.getCharacteristic();
                BluetoothGattCharacteristic ours = parent == null ? null : match(parent);
                if (ours == consumerInput) {
                    updateSub(consumerSubs, device, enable);
                } else if (ours == keyboardInput) {
                    updateSub(keyboardSubs, device, enable);
                }
                Log.i(TAG, "cccd " + (ours == keyboardInput ? "keyboard" : "consumer")
                        + (enable ? " on " : " off ") + address(device));
                if (enable) {
                    main.post(() -> onSubscribed(device));
                }
            }
            if (responseNeeded) {
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        value == null ? new byte[0] : value);
            }
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            main.post(() -> {
                if (pendingNotifies <= 0) {
                    return;
                }
                pendingNotifies--;
                if (pendingNotifies == 0) {
                    finishBatch(epoch);
                }
            });
        }

        @Override
        public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, new byte[0]);
        }
    };

    private void updateSub(Set<BluetoothDevice> subs, BluetoothDevice device, boolean enable) {
        if (enable) {
            subs.add(device);
        } else {
            subs.remove(device);
        }
    }

    private byte[] valueFor(BluetoothGattCharacteristic incoming) {
        if (REPORT_MAP.equals(incoming.getUuid())) {
            return reportMap;
        }
        BluetoothGattCharacteristic ours = match(incoming);
        if (ours == null) {
            return null;
        }
        synchronized (values) {
            byte[] value = values.get(ours);
            return value == null ? null : Arrays.copyOf(value, value.length);
        }
    }

    private byte[] descriptorValue(BluetoothDevice device, BluetoothGattDescriptor descriptor) {
        if (REPORT_REFERENCE.equals(descriptor.getUuid())) {
            BluetoothGattCharacteristic parent = descriptor.getCharacteristic();
            BluetoothGattCharacteristic ours = parent == null ? null : match(parent);
            if (ours == consumerInput) {
                return new byte[]{0x01, 0x01};
            }
            if (ours == keyboardInput) {
                return new byte[]{0x02, 0x01};
            }
            if (ours == keyboardOutput) {
                return new byte[]{0x02, 0x02};
            }
            byte[] set = descriptor.getValue();
            return set == null ? new byte[]{0x00, 0x00} : set;
        }
        if (CCCD.equals(descriptor.getUuid())) {
            BluetoothGattCharacteristic parent = descriptor.getCharacteristic();
            BluetoothGattCharacteristic ours = parent == null ? null : match(parent);
            boolean on = false;
            if (ours == consumerInput) {
                on = consumerSubs.contains(device);
            } else if (ours == keyboardInput) {
                on = keyboardSubs.contains(device);
            }
            return on
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
        }
        return descriptor.getValue() == null ? new byte[0] : descriptor.getValue();
    }

    private BluetoothGattCharacteristic match(BluetoothGattCharacteristic incoming) {
        BluetoothGattService service = incoming.getService();
        if (service == null || gattServer == null) {
            return matchById(incoming, null);
        }
        return matchById(incoming, service.getUuid());
    }

    private BluetoothGattCharacteristic matchById(BluetoothGattCharacteristic incoming, UUID serviceUuid) {
        synchronized (values) {
            if (values.containsKey(incoming)) {
                return incoming;
            }
            BluetoothGattCharacteristic found = null;
            for (BluetoothGattCharacteristic candidate : values.keySet()) {
                if (!candidate.getUuid().equals(incoming.getUuid())) {
                    continue;
                }
                if (candidate.getInstanceId() != incoming.getInstanceId()) {
                    continue;
                }
                if (serviceUuid != null && candidate.getService() != null
                        && !serviceUuid.equals(candidate.getService().getUuid())) {
                    continue;
                }
                if (found != null) {
                    return null;
                }
                found = candidate;
            }
            return found;
        }
    }

    private void respondSlice(BluetoothDevice device, int requestId, int offset, byte[] value) {
        if (offset < 0 || offset > value.length) {
            respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, new byte[0]);
            return;
        }
        int mtu = 23;
        if (device != null) {
            Integer known = mtus.get(address(device));
            if (known != null && known > mtu) {
                mtu = known;
            }
        }
        int length = Math.min(value.length - offset, Math.max(1, mtu - 1));
        respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                Arrays.copyOfRange(value, offset, offset + length));
    }

    private void respond(BluetoothDevice device, int requestId, int status, int offset, byte[] value) {
        if (gattServer == null || device == null) {
            return;
        }
        try {
            gattServer.sendResponse(device, requestId, status, offset, value);
        } catch (RuntimeException e) {
            Log.w(TAG, "sendResponse failed", e);
        }
    }

    private static String address(BluetoothDevice device) {
        try {
            return device.getAddress();
        } catch (SecurityException e) {
            return Integer.toHexString(System.identityHashCode(device));
        }
    }

    private int safeBond(BluetoothDevice device) {
        try {
            return device.getBondState();
        } catch (SecurityException e) {
            return BluetoothDevice.BOND_NONE;
        }
    }

    private void registerBondReceiver() {
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(bondReceiver, filter);
        }
    }

    private void unregisterBondReceiver() {
        try {
            context.unregisterReceiver(bondReceiver);
        } catch (IllegalArgumentException ignored) {
            // Not registered.
        }
    }

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (closed || intent == null
                    || !BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            BluetoothDevice device = deviceExtra(intent);
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
            if (device == null) {
                return;
            }
            if (state == BluetoothDevice.BOND_BONDED) {
                Log.i(TAG, "bonded " + address(device));
                main.post(() -> {
                    host.onBonded(device);
                    if (gattServer != null && !closed) {
                        try {
                            gattServer.connect(device, true);
                        } catch (SecurityException e) {
                            Log.w(TAG, "post-bond connect failed", e);
                        }
                    }
                });
            } else if (state == BluetoothDevice.BOND_NONE) {
                int previous = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
                if (previous == BluetoothDevice.BOND_BONDING && !closed) {
                    main.post(() -> host.onFailed("Pairing was cancelled"));
                }
            }
        }
    };

    private static BluetoothDevice deviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        }
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    private static UUID uuid16(int shortUuid) {
        return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", shortUuid));
    }

    private static final class Outgoing {
        final BluetoothGattCharacteristic characteristic;
        final byte[] value;

        Outgoing(BluetoothGattCharacteristic characteristic, byte[] value) {
            this.characteristic = characteristic;
            this.value = value;
        }
    }
}
