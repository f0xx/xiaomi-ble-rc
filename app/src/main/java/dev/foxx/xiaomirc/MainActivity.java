package dev.foxx.xiaomirc;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import dev.foxx.xiaomirc.hid.RemoteKey;
import dev.foxx.xiaomirc.ui.RemoteSkinView;

public final class MainActivity extends AppCompatActivity implements RemoteService.Listener {
    private static final int REQUEST_PERMISSIONS = 1;
    private static final int REQUEST_ENABLE_BT = 2;

    private TextView status;
    private TextView detail;
    private Button pair;
    private RemoteSkinView skin;
    private RemoteService service;
    private boolean bound;
    private boolean startWhenReady;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RemoteService.LocalBinder) binder).getService();
            service.setListener(MainActivity.this);
            bound = true;
            render();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            render();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        detail = findViewById(R.id.detail);
        pair = findViewById(R.id.pair);
        skin = findViewById(R.id.skin);
        skin.setListener(this::onRemoteKey);
        pair.setOnClickListener(v -> onPairClicked());
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (RemoteService.isEnabled(this) && bluetoothGranted()) {
            startRemoteService();
        }
        bindIfRunning();
    }

    @Override
    protected void onStop() {
        if (service != null) {
            service.setListener(null);
        }
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        service = null;
        super.onStop();
    }

    @Override
    public void onStateChanged() {
        render();
    }

    private void onPairClicked() {
        if (RemoteService.isEnabled(this) || (service != null && service.isSessionOpen())) {
            if (service != null) {
                service.shutdown();
            } else {
                Intent stop = new Intent(this, RemoteService.class);
                stop.setAction(RemoteService.ACTION_STOP);
                startService(stop);
            }
            render();
            return;
        }
        if (!bluetoothGranted()) {
            startWhenReady = true;
            ActivityCompat.requestPermissions(this, bluetoothPermissions(), REQUEST_PERMISSIONS);
            return;
        }
        requestNotifications();
        if (!bluetoothEnabled()) {
            startWhenReady = true;
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT);
            return;
        }
        startWhenReady = false;
        startRemoteService();
        bindIfRunning();
    }

    private void startRemoteService() {
        ContextCompat.startForegroundService(this, new Intent(this, RemoteService.class));
    }

    private void bindIfRunning() {
        if (bound || !RemoteService.isEnabled(this)) {
            return;
        }
        bindService(new Intent(this, RemoteService.class), connection, BIND_AUTO_CREATE);
    }

    private void onRemoteKey(RemoteKey key, boolean down) {
        if (service != null) {
            service.setKey(key, down);
        }
    }

    private void render() {
        boolean running = service != null && service.isSessionOpen();
        if (service != null) {
            status.setText(service.getTitle());
            detail.setText(service.getDetail());
            skin.setLinkState(service.isAdvertising(), service.isReady());
        } else if (RemoteService.isEnabled(this)) {
            status.setText("XMRM-ML");
            detail.setText("Reconnecting the remote…");
        } else {
            status.setText("XMRM-ML");
            detail.setText("Tap Pair. On the TV, add a Bluetooth device named XMRM-ML. No Wi-Fi pairing.");
            skin.setLinkState(false, false);
        }
        boolean enabled = running || RemoteService.isEnabled(this);
        pair.setText(enabled ? "Stop" : "Pair");
    }

    private boolean bluetoothGranted() {
        if (Build.VERSION.SDK_INT < 31) {
            return true;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String[] bluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        }
        return new String[]{
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
        };
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_PERMISSIONS);
        }
    }

    private boolean bluetoothEnabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS && startWhenReady && bluetoothGranted()) {
            onPairClicked();
        } else if (requestCode == REQUEST_PERMISSIONS && startWhenReady && !bluetoothGranted()) {
            startWhenReady = false;
            detail.setText("Bluetooth permission is required so the phone can pair as a remote.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT && startWhenReady) {
            if (bluetoothEnabled()) {
                onPairClicked();
            } else {
                startWhenReady = false;
                detail.setText("Bluetooth is off. Turn it on to pair with the TV.");
            }
        }
    }
}
