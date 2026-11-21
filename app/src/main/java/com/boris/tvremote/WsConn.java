package com.boris.tvremote;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Minimal WebSocket client (RFC 6455), text frames only.
 * TLS accepts the TV's self-signed certificate: LG TVs on the home network
 * only offer self-signed certs, and this is only ever used for the TV's local IP.
 */
public class WsConn {
    public interface Handler {
        void onText(WsConn c, String text);
        void onClosed(WsConn c, Exception reason);
    }

    private final Socket sock;
    private final DataInputStream in;
    private final OutputStream out;
    private final Handler handler;
    private final Object writeLock = new Object();
    private final SecureRandom rnd = new SecureRandom();
    private volatile boolean closed;

    private WsConn(Socket sock, Handler handler) throws IOException {
        this.sock = sock;
        this.in = new DataInputStream(sock.getInputStream());
        this.out = sock.getOutputStream();
        this.handler = handler;
    }

    public static WsConn open(String url, int timeoutMs, Handler handler) throws IOException {
        URI u = URI.create(url);
        boolean tls = "wss".equalsIgnoreCase(u.getScheme());
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
        String path = (u.getRawPath() == null || u.getRawPath().isEmpty()) ? "/" : u.getRawPath();
        if (u.getRawQuery() != null) path += "?" + u.getRawQuery();

        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), timeoutMs);
        raw.setTcpNoDelay(true);
        raw.setSoTimeout(timeoutMs);
        Socket s = raw;
        try {
            if (tls) {
                SSLSocket ss = (SSLSocket) trustAll().getSocketFactory().createSocket(raw, host, port, true);
                ss.setSoTimeout(timeoutMs);
                ss.startHandshake();
                s = ss;
            }
            WsConn c = new WsConn(s, handler);
            c.handshake(host, port, path);
            s.setSoTimeout(0);
            Thread t = new Thread(c::readLoop, "ws-reader");
            t.setDaemon(true);
            t.start();
            return c;
        } catch (IOException | RuntimeException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    /** TLS context that accepts the TV's self-signed certificate (used only for the TV's own IP). */
    static SSLContext tvTls() throws IOException { return trustAll(); }

    private static SSLContext trustAll() throws IOException {
        try {
            TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IOException("TLS init failed", e);
        }
    }

    private void handshake(String host, int port, String path) throws IOException {
        byte[] k = new byte[16];
        rnd.nextBytes(k);
        String key = Base64.getEncoder().encodeToString(k);
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        ByteArrayOutputStream hdr = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) throw new IOException("Connection closed during handshake");
            hdr.write(b);
            if (hdr.size() > 16384) throw new IOException("Handshake response too large");
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') state++;
            else state = (b == '\r') ? 1 : 0;
        }
        String resp = hdr.toString("ISO-8859-1");
        String first = resp.split("\r\n", 2)[0];
        if (!first.contains(" 101")) throw new IOException("WebSocket handshake rejected: " + first);
    }

    public boolean isOpen() { return !closed; }

    public void sendText(String text) throws IOException {
        if (closed) throw new IOException("Connection closed");
        sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 14);
        f.write(0x80 | opcode);
        int n = payload.length;
        if (n < 126) {
            f.write(0x80 | n);
        } else if (n < 65536) {
            f.write(0x80 | 126);
            f.write((n >>> 8) & 0xff);
            f.write(n & 0xff);
        } else {
            f.write(0x80 | 127);
            for (int sh = 56; sh >= 0; sh -= 8) f.write((int) (((long) n >>> sh) & 0xff));
        }
        byte[] mask = new byte[4];
        rnd.nextBytes(mask);
        f.write(mask, 0, 4);
        for (int k = 0; k < n; k++) f.write(payload[k] ^ mask[k & 3]);
        synchronized (writeLock) {
            out.write(f.toByteArray());
            out.flush();
        }
    }

    private void readLoop() {
        Exception reason = null;
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        int msgOp = 0;
        try {
            while (!closed) {
                int b0 = in.readUnsignedByte();
                int b1 = in.readUnsignedByte();
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0f;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7f;
                if (len == 126) len = in.readUnsignedShort();
                else if (len == 127) len = in.readLong();
                if (len < 0 || len > 16L * 1024 * 1024) throw new IOException("Frame too large");
                byte[] mask = null;
                if (masked) { mask = new byte[4]; in.readFully(mask); }
                byte[] data = new byte[(int) len];
                in.readFully(data);
                if (mask != null) for (int k = 0; k < data.length; k++) data[k] ^= mask[k & 3];

                if (op == 0x8) { break; }
                if (op == 0x9) { sendFrame(0xA, data); continue; }
                if (op == 0xA) { continue; }
                if (op == 0x1 || op == 0x2) { msg.reset(); msgOp = op; }
                if (op == 0x0 || op == 0x1 || op == 0x2) {
                    msg.write(data, 0, data.length);
                    if (fin) {
                        if (msgOp == 0x1) {
                            String text = new String(msg.toByteArray(), StandardCharsets.UTF_8);
                            try { handler.onText(this, text); } catch (RuntimeException ignored) { }
                        }
                        msg.reset();
                    }
                }
            }
        } catch (Exception e) {
            reason = e;
        }
        closeQuietly();
        handler.onClosed(this, reason);
    }

    public void close() {
        if (!closed) {
            try { sendFrame(0x8, new byte[0]); } catch (IOException ignored) { }
        }
        closeQuietly();
    }

    private void closeQuietly() {
        closed = true;
        try { sock.close(); } catch (IOException ignored) { }
    }
}
