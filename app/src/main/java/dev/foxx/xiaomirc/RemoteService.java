package dev.foxx.xiaomirc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import dev.foxx.xiaomirc.hid.HidPeripheral;
import dev.foxx.xiaomirc.hid.RemoteKey;

/**
 * Keeps the BLE HID peripheral alive while the phone is in a pocket, so a
 * power press can still wake a bonded TV.
 */
public final class RemoteService extends Service implements HidPeripheral.Host {
    public static final String ACTION_STOP = "dev.foxx.xiaomirc.STOP";
    public static final String ADVERTISED_NAME = "XMRM-ML";

    private static final String PREFS = "remote";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ORIGINAL_NAME = "original_bt_name";
    private static final String KEY_HOST = "host_address";
    private static final String CHANNEL_ID = "remote";
    private static final int NOTIFICATION_ID = 1;

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());

    private HidPeripheral peripheral;
    private Listener listener;
    private PowerManager.WakeLock wakeLock;
    private String title = "XMRM-ML";
    private String detail = "Tap Pair, then add XMRM-ML on the TV.";
    private boolean ready;
    private boolean advertising;
    private boolean pendingPower;

    public interface Listener {
        void onStateChanged();
    }

    public final class LocalBinder extends Binder {
        public RemoteService getService() {
            return RemoteService.this;
        }
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        prefs(this).edit().putBoolean(KEY_ENABLED, true).apply();
        startInForeground();
        if (peripheral == null) {
            begin();
        }
        return START_STICKY;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isAdvertising() {
        return advertising;
    }

    public boolean isSessionOpen() {
        return peripheral != null;
    }

    public void shutdown() {
        pendingPower = false;
        prefs(this).edit().putBoolean(KEY_ENABLED, false).apply();
        closePeripheral();
        restoreAdapterName();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    public void setKey(RemoteKey key, boolean down) {
        HidPeripheral current = peripheral;
        if (current == null) {
            return;
        }
        if (down && key == RemoteKey.POWER && !current.canSend(key)) {
            pendingPower = true;
            acquireWake();
            publish("Waking the TV",
                    "XMRM-ML is advertising. The TV reconnects from standby, then the power key is sent.");
            return;
        }
        if (down) {
            current.press(key);
        } else {
            current.release(key);
        }
    }

    @Override
    public void onAdvertising() {
        advertising = true;
        String host = prefs(this).getString(KEY_HOST, null);
        if (host == null) {
            publish("Visible as XMRM-ML",
                    "On the TV: Settings → Remotes & accessories → Add device, then choose XMRM-ML. Accept the pair prompt on this phone. This phone's Bluetooth name is XMRM-ML until you tap Stop.");
        } else if (!ready) {
            publish("Waiting for the TV",
                    "Already paired. Press Power to wake it from standby. The phone has to stay nearby.");
        }
    }

    @Override
    public void onBonding(BluetoothDevice device) {
        publish("Pairing", "Accept the Bluetooth prompt on this phone.");
    }

    @Override
    public void onBonded(BluetoothDevice device) {
        try {
            prefs(this).edit().putString(KEY_HOST, device.getAddress()).apply();
        } catch (SecurityException ignored) {
            // Name is enough for the next session; the address is an optimization.
        }
        publish("Paired", "Finishing HID setup with the TV.");
    }

    @Override
    public void onConnected(BluetoothDevice device) {
        String name = deviceName(device);
        publish("TV connected", name == null ? "Setting up HID reports." : "Setting up HID with " + name + ".");
    }

    @Override
    public void onReady(BluetoothDevice device) {
        ready = true;
        advertising = true;
        String name = deviceName(device);
        publish(name == null ? "Connected" : "Connected to " + name,
                "Buttons are live. Voice opens the assistant on the TV; it does not use this phone's microphone.");
        if (pendingPower && peripheral != null) {
            pendingPower = false;
            main.postDelayed(() -> {
                if (peripheral == null) {
                    return;
                }
                peripheral.press(RemoteKey.POWER);
                main.postDelayed(() -> {
                    if (peripheral != null) {
                        peripheral.release(RemoteKey.POWER);
                    }
                }, 90);
            }, 200);
        }
    }

    @Override
    public void onDisconnected() {
        ready = false;
        String host = prefs(this).getString(KEY_HOST, null);
        if (host != null) {
            publish("Waiting for the TV",
                    "Link dropped. Press Power to wake it, or it will reconnect on its own.");
        } else {
            publish("Visible as XMRM-ML",
                    "The TV disconnected before pairing finished. Add XMRM-ML again from the TV's Bluetooth list.");
        }
    }

    @Override
    public void onFailed(String message) {
        publish("Remote paused", message);
    }

    private void begin() {
        ready = false;
        peripheral = new HidPeripheral(this, this);
        publish("Starting", "Turning this phone into an XMRM-ML Bluetooth remote.");
        applyAdvertisedName();
        peripheral.open();
        main.postDelayed(this::reconnectSaved, 1200);
    }

    private void closePeripheral() {
        HidPeripheral current = peripheral;
        peripheral = null;
        ready = false;
        advertising = false;
        if (current != null) {
            current.close();
        }
    }

    private void reconnectSaved() {
        if (peripheral == null) {
            return;
        }
        String address = prefs(this).getString(KEY_HOST, null);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (address == null || adapter == null) {
            return;
        }
        try {
            peripheral.remember(adapter.getRemoteDevice(address));
        } catch (IllegalArgumentException ignored) {
            prefs(this).edit().remove(KEY_HOST).apply();
        }
    }

    private void applyAdvertisedName() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return;
        }
        try {
            String current = adapter.getName();
            SharedPreferences prefs = prefs(this);
            if (!prefs.contains(KEY_ORIGINAL_NAME) && current != null && !ADVERTISED_NAME.equals(current)) {
                prefs.edit().putString(KEY_ORIGINAL_NAME, current).apply();
            }
            adapter.setName(ADVERTISED_NAME);
        } catch (SecurityException e) {
            publish("Bluetooth name unchanged",
                    "The TV may list this phone under its usual name. Pair that entry; the HID service is still XMRM-ML.");
        }
    }

    private void restoreAdapterName() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        SharedPreferences prefs = prefs(this);
        String original = prefs.getString(KEY_ORIGINAL_NAME, null);
        if (adapter != null && original != null) {
            try {
                adapter.setName(original);
            } catch (SecurityException ignored) {
                return;
            }
        }
        prefs.edit().remove(KEY_ORIGINAL_NAME).apply();
    }

    private void acquireWake() {
        if (wakeLock == null) {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power == null) {
                return;
            }
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xiaomirc:power");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(20_000L);
    }

    private String deviceName(BluetoothDevice device) {
        try {
            String name = device.getName();
            if (name == null || name.trim().isEmpty()) {
                return null;
            }
            return name.trim();
        } catch (SecurityException e) {
            return null;
        }
    }

    private void publish(String title, String detail) {
        this.title = title;
        this.detail = detail;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && peripheral != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
        if (listener != null) {
            listener.onStateChanged();
        }
    }

    private void startInForeground() {
        createChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForegroundTyped(notification);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startForegroundTyped(Notification notification) {
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "XMRM-ML remote", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Stays visible while the phone is acting as the TV remote");
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, RemoteService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_remote)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(detail))
                .setContentIntent(content)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopAction)
                .build();
    }
}
