package sk.bambuwidget;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Minimal MQTT 3.1.1 client over TLS: connect, subscribe, publish (QoS 0), receive. */
final class MiniMqtt {
    static final Charset UTF8 = Charset.forName("UTF-8");

    static final class AuthException extends IOException {
        AuthException(String m) { super(m); }
    }

    static final class Message {
        final String topic;
        final byte[] payload;
        Message(String t, byte[] p) { topic = t; payload = p; }
    }

    private SSLSocket socket;
    private DataInputStream in;
    private OutputStream out;
    private int packetId = 1;

    void connect(String host, int port, String clientId, String user, String pass, int timeoutMs) throws IOException {
        socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, port);
        socket.setSoTimeout(timeoutMs);
        socket.startHandshake();
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.getSession())) {
            socket.close();
            throw new IOException("TLS certificate does not match " + host);
        }
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();

        ByteArrayOutputStream b = new ByteArrayOutputStream();
        str(b, "MQTT");
        b.write(4);            // protocol level 3.1.1
        b.write(0xC2);         // username, password, clean session
        b.write(0); b.write(60); // keep alive 60 s
        str(b, clientId);
        str(b, user);
        str(b, pass);
        send(0x10, b.toByteArray());

        int type = in.readUnsignedByte();
        int len = readLength();
        byte[] body = new byte[len];
        in.readFully(body);
        if ((type >> 4) != 2 || len < 2) throw new IOException("Unexpected reply from broker");
        int rc = body[1] & 0xFF;
        if (rc == 4 || rc == 5) throw new AuthException("Bambu Cloud rejected the sign-in");
        if (rc != 0) throw new IOException("Broker refused connection (" + rc + ")");
    }

    void subscribe(String topic) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int id = packetId++;
        b.write(id >> 8); b.write(id & 0xFF);
        str(b, topic);
        b.write(0); // QoS 0
        send(0x82, b.toByteArray());
    }

    void publish(String topic, String payload) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        str(b, topic);
        byte[] p = payload.getBytes(UTF8);
        b.write(p, 0, p.length);
        send(0x30, b.toByteArray());
    }

    /** Next PUBLISH from the broker; other packets (SUBACK, PINGRESP) are skipped. Throws on timeout. */
    Message read() throws IOException {
        while (true) {
            int type = in.readUnsignedByte();
            int len = readLength();
            byte[] body = new byte[len];
            in.readFully(body);
            if ((type >> 4) != 3) continue;
            int tlen = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
            String topic = new String(body, 2, tlen, UTF8);
            int pos = 2 + tlen;
            if (((type >> 1) & 3) > 0) pos += 2; // QoS > 0 carries a packet id
            byte[] payload = new byte[len - pos];
            System.arraycopy(body, pos, payload, 0, payload.length);
            return new Message(topic, payload);
        }
    }

    void setTimeout(int ms) throws IOException { socket.setSoTimeout(ms); }

    void close() {
        try { send(0xE0, new byte[0]); } catch (Exception ignored) { }
        try { socket.close(); } catch (Exception ignored) { }
    }

    private void send(int header, byte[] body) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(header);
        int len = body.length;
        do {
            int d = len % 128;
            len /= 128;
            if (len > 0) d |= 0x80;
            b.write(d);
        } while (len > 0);
        b.write(body, 0, body.length);
        out.write(b.toByteArray());
        out.flush();
    }

    private int readLength() throws IOException {
        int mult = 1, value = 0, d;
        do {
            d = in.readUnsignedByte();
            value += (d & 127) * mult;
            mult *= 128;
        } while ((d & 128) != 0);
        return value;
    }

    private static void str(ByteArrayOutputStream b, String s) {
        byte[] d = s.getBytes(UTF8);
        b.write(d.length >> 8);
        b.write(d.length & 0xFF);
        b.write(d, 0, d.length);
    }
}
