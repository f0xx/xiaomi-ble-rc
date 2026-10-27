package dev.foxx.xiaomirc.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Learns the TV's Wi-Fi MAC without a network pairing step. Google TV advertises
 * {@code _androidtvremote2._tcp} with a {@code bt} attribute equal to the Bluetooth
 * address we already bonded. While the set is on, that match leads to its Cast
 * status page, which publishes the network adapter address used for Wake-on-WLAN.
 */
public final class TvLan {
    private static final String TAG = "XiaomiRc";
    private static final String PREFS = "remote";
    private static final String KEY_MAC = "net_mac";
    private static final String KEY_IP = "net_ip";
    private static final String SERVICE = "_androidtvremote2._tcp";
    /** Family Room TV, measured on the ethernet segment. Cast status reports this NIC as all zeros. */
    private static final String KNOWN_BT = "50:e4:78:6e:67:75";
    private static final String KNOWN_MAC = "78:53:33:50:f3:d5";
    private static final String KNOWN_IP = "192.168.200.240";
    private static final String KEY_HOST = "host_address";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private NsdManager nsd;
    private WifiManager.MulticastLock multicast;
    private NsdManager.DiscoveryListener discovery;
    private boolean discovering;
    private String bluetoothAddress;
    private String bluetoothName;
    private Runnable onLearned;

    public TvLan(Context context) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
    }

    public boolean hasMac() {
        return mac() != null;
    }

    public String mac() {
        return prefs().getString(KEY_MAC, null);
    }

    /** Sends a magic packet when a network MAC is already known. */
    public boolean wake() {
        rememberKnownEthernet();
        String mac = mac();
        if (mac == null) {
            return false;
        }
        String ip = prefs().getString(KEY_IP, null);
        executor.execute(() -> WakeOnLan.send(context, mac, WakeOnLan.relayTargets(ip)));
        return true;
    }

    /** Look up the bonded TV on the LAN. {@code onLearned} runs on the main thread if a new MAC is stored. */
    public void learn(String bluetoothAddress, String bluetoothName, Runnable onLearned) {
        main.post(() -> start(bluetoothAddress, bluetoothName, onLearned));
    }

    public void close() {
        main.post(this::stop);
        executor.shutdownNow();
    }

    private void start(String bluetoothAddress, String bluetoothName, Runnable onLearned) {
        this.bluetoothAddress = bluetoothAddress;
        this.bluetoothName = bluetoothName;
        this.onLearned = onLearned;
        rememberKnownEthernet();
        if (discovering) {
            return;
        }
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) {
            return;
        }
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicast = wifi.createMulticastLock("xiaomirc:mdns");
            multicast.setReferenceCounted(false);
            multicast.acquire();
        }
        discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "mdns start " + errorCode);
                stop();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                discovering = false;
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.i(TAG, "mdns " + serviceType);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                discovering = false;
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                        Log.w(TAG, "mdns resolve " + errorCode);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo info) {
                        if (!matches(info)) {
                            return;
                        }
                        String host = info.getHost() == null ? null : info.getHost().getHostAddress();
                        Log.i(TAG, "mdns matched " + info.getServiceName() + " " + host);
                        stop();
                        if (host != null) {
                            executor.execute(() -> inspect(host));
                        }
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
            }
        };
        try {
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery);
            discovering = true;
            main.postDelayed(this::stop, 12_000);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "mdns", e);
            stop();
        }
    }

    private boolean matches(NsdServiceInfo info) {
        String bt = attribute(info, "bt");
        if (bt != null && bluetoothAddress != null) {
            return same(bt, bluetoothAddress);
        }
        String name = attribute(info, "fn");
        if (name == null || name.isEmpty()) {
            name = info.getServiceName();
        }
        return bluetoothName != null && !bluetoothName.isEmpty() && bluetoothName.equalsIgnoreCase(name);
    }

    private void inspect(String ip) {
        String mac = eureka(ip);
        if (mac == null || same(mac, bluetoothAddress)) {
            poke(ip);
            String fromArp = arp(ip);
            if (fromArp != null && !same(fromArp, bluetoothAddress)) {
                mac = fromArp;
            } else if (same(mac, bluetoothAddress)) {
                mac = null;
            }
        }
        if (mac == null) {
            Log.i(TAG, "no network mac for " + ip);
            return;
        }
        prefs().edit().putString(KEY_MAC, mac).putString(KEY_IP, ip).apply();
        Log.i(TAG, "network mac " + mac + " at " + ip);
        Runnable done = onLearned;
        if (done != null) {
            main.post(done);
        }
    }

    private String eureka(String ip) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://" + ip + ":8008/setup/eureka_info");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setInstanceFollowRedirects(false);
            int code = connection.getResponseCode();
            if (code != 200) {
                Log.i(TAG, "eureka " + code);
                return null;
            }
            InputStream stream = connection.getInputStream();
            byte[] body = new byte[8192];
            int length = stream.read(body);
            stream.close();
            if (length <= 0) {
                return null;
            }
            JSONObject json = new JSONObject(new String(body, 0, length, StandardCharsets.UTF_8));
            Log.i(TAG, "eureka name=" + json.optString("name"));
            return WakeOnLan.normalize(json.optString("mac_address", null));
        } catch (Exception e) {
            Log.i(TAG, "eureka " + e.getClass().getSimpleName());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void poke(String ip) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, 8008), 1500);
        } catch (Exception ignored) {
            // The ARP entry is created even when the port refuses the connection.
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
                // Already closed.
            }
        }
    }

    private String arp(String ip) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 4 && ip.equals(parts[0])) {
                    return WakeOnLan.normalize(parts[3]);
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "arp " + e.getClass().getSimpleName());
        }
        return null;
    }

    private void stop() {
        main.removeCallbacks(this::stop);
        if (nsd != null && discovery != null && discovering) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (IllegalArgumentException ignored) {
                // Discovery was already stopped.
            }
        }
        discovering = false;
        discovery = null;
        if (multicast != null && multicast.isHeld()) {
            multicast.release();
        }
        multicast = null;
    }

    private static String attribute(NsdServiceInfo info, String key) {
        Map<String, byte[]> attributes = info.getAttributes();
        if (attributes == null) {
            return null;
        }
        byte[] value = attributes.get(key);
        if (value == null || value.length == 0) {
            return null;
        }
        return new String(value, StandardCharsets.UTF_8);
    }

    private static boolean same(String left, String right) {
        String a = WakeOnLan.normalize(left);
        String b = WakeOnLan.normalize(right);
        return a != null && a.equals(b);
    }

    /** The ethernet NIC does not appear in Cast status. Keep the address ARP returned on that LAN. */
    private void rememberKnownEthernet() {
        if (mac() != null) {
            return;
        }
        String bonded = bluetoothAddress != null ? bluetoothAddress : prefs().getString(KEY_HOST, null);
        if (!KNOWN_BT.equals(WakeOnLan.normalize(bonded))) {
            return;
        }
        prefs().edit().putString(KEY_MAC, KNOWN_MAC).putString(KEY_IP, KNOWN_IP).apply();
        Log.i(TAG, "network mac " + KNOWN_MAC + " at " + KNOWN_IP);
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
