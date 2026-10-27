package dev.foxx.xiaomirc.net;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Locale;

/** Magic packet for Wake-on-LAN / Wake-on-WLAN. The address has to be the TV's network adapter. */
public final class WakeOnLan {
    private static final String TAG = "XiaomiRc";

    private WakeOnLan() {
    }

    public static byte[] magic(String mac) {
        byte[] address = parse(mac);
        byte[] packet = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) {
            packet[i] = (byte) 0xFF;
        }
        for (int copy = 0; copy < 16; copy++) {
            System.arraycopy(address, 0, packet, 6 + copy * 6, 6);
        }
        return packet;
    }

    public static String normalize(String mac) {
        try {
            byte[] parsed = parse(mac);
            StringBuilder builder = new StringBuilder(17);
            for (int i = 0; i < parsed.length; i++) {
                if (i > 0) {
                    builder.append(':');
                }
                builder.append(String.format(Locale.US, "%02x", parsed[i] & 0xFF));
            }
            String text = builder.toString();
            if ("00:00:00:00:00:00".equals(text) || "ff:ff:ff:ff:ff:ff".equals(text)) {
                return null;
            }
            return text;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static void send(Context context, String mac, String... extraHosts) {
        byte[] packet = magic(mac);
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            deliver(socket, packet, InetAddress.getByName("255.255.255.255"));
            InetAddress directed = subnetBroadcast(context);
            if (directed != null) {
                deliver(socket, packet, directed);
            }
            if (extraHosts != null) {
                for (String host : extraHosts) {
                    if (host == null || host.isEmpty()) {
                        continue;
                    }
                    deliver(socket, packet, InetAddress.getByName(host));
                }
            }
            Log.i(TAG, "wol sent " + normalize(mac));
        } catch (IOException e) {
            Log.w(TAG, "wol", e);
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    /**
     * Addresses on the TV's own subnet. The first is that subnet's router, which
     * can turn this unicast packet into an ethernet broadcast. A broadcast sent
     * from Wi-Fi never reaches that cable.
     */
    public static String[] relayTargets(String ip) {
        String[] parts = ip == null ? new String[0] : ip.split("\\.");
        if (parts.length != 4) {
            return new String[0];
        }
        for (String part : parts) {
            try {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) {
                    return new String[0];
                }
            } catch (NumberFormatException e) {
                return new String[0];
            }
        }
        String prefix = parts[0] + "." + parts[1] + "." + parts[2] + ".";
        return new String[]{prefix + "1", prefix + "255", ip};
    }

    private static void deliver(DatagramSocket socket, byte[] packet, InetAddress address) throws IOException {
        socket.send(new DatagramPacket(packet, packet.length, address, 9));
        socket.send(new DatagramPacket(packet, packet.length, address, 7));
    }

    private static byte[] parse(String mac) {
        String hex = mac == null ? "" : mac.replace(":", "").replace("-", "").trim();
        if (!hex.matches("(?i)[0-9a-f]{12}")) {
            throw new IllegalArgumentException(mac == null ? "" : mac);
        }
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static InetAddress subnetBroadcast(Context context) {
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            return null;
        }
        DhcpInfo dhcp = wifi.getDhcpInfo();
        if (dhcp == null || dhcp.ipAddress == 0 || dhcp.netmask == 0) {
            return null;
        }
        int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
        byte[] bytes = new byte[]{
                (byte) (broadcast & 0xFF),
                (byte) ((broadcast >> 8) & 0xFF),
                (byte) ((broadcast >> 16) & 0xFF),
                (byte) ((broadcast >> 24) & 0xFF)
        };
        try {
            return InetAddress.getByAddress(bytes);
        } catch (IOException e) {
            return null;
        }
    }
}
