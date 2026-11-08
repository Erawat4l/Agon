package ai.arena.webapp;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The addresses this phone can be reached at on its own network.
 *
 * It exists for one situation: a preview link copied from a laptop says
 * `http://localhost:3000`, which on a phone means the phone itself and can never load. The
 * server is on the laptop, so the laptop is the address to use - and the app cannot invent
 * that address, but it can show the phone's own, which sits on the same network and tells
 * the user (and the laptop's dev server) what to bind to and what to type.
 *
 * Two kinds of entry come back:
 *
 *  - this phone's wifi/LAN addresses (192.168.x.x, 10.x.x.x), which are also the numbers a
 *    dev server has to be told about to accept the connection;
 *  - the loopback entry, for a server actually running on the phone (Termux), which is the
 *    one case where localhost is correct.
 *
 * Nothing here talks to the network: these are the interfaces the phone already has.
 */
public final class LanAddresses {

    /** One address, with the wording that explains what it is for. */
    public static final class Entry {
        public final String label;
        public final String url;

        Entry(String label, String url) {
            this.label = label;
            this.url = url;
        }
    }

    private LanAddresses() {
    }

    /** Every usable address, wifi first, loopback last. Never empty. */
    public static List<Entry> onDevice(Context context) {
        List<Entry> out = new ArrayList<>();
        String wifi = wifiAddress(context);
        for (String ip : lanAddresses()) {
            if (ip.equals(wifi)) {
                out.add(0, new Entry(labelFor(context, R.string.local_help_wifi), "http://" + ip));
            } else {
                out.add(new Entry(labelFor(context, R.string.local_help_lan), "http://" + ip));
            }
        }
        out.add(new Entry(labelFor(context, R.string.local_help_this_phone_call),
                "http://127.0.0.1"));
        return out;
    }

    private static String labelFor(Context context, int id) {
        try {
            return context.getString(id);
        } catch (RuntimeException missing) {
            return "";
        }
    }

    /**
     * The phone's own wifi address, or null.
     *
     * The deprecated WifiManager call is used first because it keeps the address available
     * while wifi is being set up; the interface walk below is the fallback for any device
     * that refuses it.
     */
    @SuppressWarnings("deprecation")
    public static String wifiAddress(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && wifi.isWifiEnabled()) {
                int ip = wifi.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return String.format("%d.%d.%d.%d", ip & 0xff, (ip >> 8) & 0xff,
                            (ip >> 16) & 0xff, (ip >> 24) & 0xff);
                }
            }
        } catch (RuntimeException ignored) {
            // a device that hides this behind a permission is handled by the walk below
        }
        return null;
    }

    /** Every IPv4 address on an up, non-loopback interface. */
    public static List<String> lanAddresses() {
        List<String> found = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    String ip = address.getHostAddress();
                    if (ip != null && !found.contains(ip)) found.add(ip);
                }
            }
        } catch (Exception ignored) {
            // no interfaces visible: the loopback entry is still offered
        }
        return found;
    }

    /**
     * The port in a URL, or "" when it says nothing.
     *
     * A preview link is nearly always a port, and the address the app offers has to keep it:
     * `http://192.168.1.20` without `:3000` is a different server (or none).
     */
    public static String portOf(String url) {
        if (url == null) return "";
        int scheme = url.indexOf("://");
        int start = scheme >= 0 ? scheme + 3 : 0;
        int slash = url.indexOf('/', start);
        String authority = slash >= 0 ? url.substring(start, slash) : url.substring(start);
        int colon = authority.lastIndexOf(':');
        if (colon < 0) return "";
        String port = authority.substring(colon + 1);
        return port.matches("\\d{1,5}") ? ":" + port : "";
    }
}
