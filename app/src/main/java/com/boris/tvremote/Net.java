package com.boris.tvremote;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

/** Wake-on-LAN and SSDP discovery of LG webOS TVs. */
public final class Net {
    private Net() { }

    public static byte[] parseMac(String mac) {
        String hex = mac == null ? "" : mac.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() != 12) throw new IllegalArgumentException("Неверный MAC-адрес: " + mac);
        byte[] b = new byte[6];
        for (int k = 0; k < 6; k++) b[k] = (byte) Integer.parseInt(hex.substring(k * 2, k * 2 + 2), 16);
        return b;
    }

    public static byte[] magicPacket(String mac) {
        byte[] m = parseMac(mac);
        byte[] p = new byte[6 + 16 * 6];
        for (int k = 0; k < 6; k++) p[k] = (byte) 0xff;
        for (int r = 0; r < 16; r++) System.arraycopy(m, 0, p, 6 + r * 6, 6);
        return p;
    }

    /** Sends the magic packet several times to each broadcast address on ports 9 and 7. */
    public static void wake(String mac, List<String> broadcasts) throws IOException {
        byte[] p = magicPacket(mac);
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            for (int round = 0; round < 3; round++) {
                for (String addr : broadcasts) {
                    InetAddress a = InetAddress.getByName(addr);
                    s.send(new DatagramPacket(p, p.length, a, 9));
                    s.send(new DatagramPacket(p, p.length, a, 7));
                }
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    /** SSDP search for webOS TVs; returns their IP addresses (needs a multicast lock on Android). */
    public static List<String> discover(int waitMs) throws IOException {
        String req = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: urn:lge-com:service:webos-second-screen:1\r\n\r\n";
        byte[] data = req.getBytes(StandardCharsets.US_ASCII);
        Set<String> found = new LinkedHashSet<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(300);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            long end = System.currentTimeMillis() + waitMs;
            long nextSend = 0;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend) {
                    s.send(new DatagramPacket(data, data.length, group, 1900));
                    nextSend = System.currentTimeMillis() + 1000;
                }
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(in);
                    String txt = new String(in.getData(), 0, in.getLength(), StandardCharsets.US_ASCII).toLowerCase();
                    if (txt.contains("webos") || txt.contains("lge")) found.add(in.getAddress().getHostAddress());
                } catch (SocketTimeoutException ignored) { }
            }
        }
        return new ArrayList<>(found);
    }
}
