package com.microjainslee.ra.freeswitch.esl;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal ESL framing: auth + one-shot {@code api}/{@code bgapi}, or long-lived event reader.
 * Soft-fails with body prefix {@code -ERR} when FS is down.
 */
public final class EslSession implements AutoCloseable {

    private final String host;
    private final int port;
    private final String password;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    private Socket socket;
    private InputStream in;
    /** Receive buffer: unconsumed bytes are {@code [pos, lim)}. */
    private byte[] buf = new byte[64 * 1024];
    private int pos;
    private int lim;
    private int soTimeout = -1;
    private BufferedWriter out;
    private volatile boolean live;

    public EslSession(String host, int port, String password, int connectTimeoutMs, int readTimeoutMs) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public boolean live() {
        return live;
    }

    public void connect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        setSoTimeout(readTimeoutMs);
        in = socket.getInputStream();
        pos = 0;
        lim = 0;
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        readHeaders(); // greeting
        writeLine("auth " + password);
        String auth = readHeaders().getOrDefault("_raw", "");
        if (!auth.contains("+OK")) {
            live = false;
            throw new IOException("ESL authentication failed");
        }
        live = true;
    }

    public String api(String command) throws IOException {
        ensureConnected();
        writeLine("api " + command);
        return readBodyOrHeaders();
    }

    public String bgapi(String command) throws IOException {
        ensureConnected();
        writeLine("bgapi " + command);
        return readBodyOrHeaders();
    }

    public void subscribe(String events) throws IOException {
        ensureConnected();
        writeLine("event plain " + events);
        readHeaders(); // ack
    }

    /**
     * Blocking read of next ESL message as header map. Empty map on EOF.
     * Puts concatenated raw text under {@code _raw}; Event-Name under {@code Event-Name}.
     */
    public Map<String, String> readNextMessage() throws IOException {
        ensureConnected();
        // Event socket: disable short read timeout for inbound wait — use 0 = infinite
        setSoTimeout(0);
        return readHeaders();
    }

    private void ensureConnected() throws IOException {
        if (socket == null || socket.isClosed()) {
            connect();
        }
    }

    private void writeLine(String line) throws IOException {
        out.write(line);
        out.write("\n\n");
        out.flush();
    }

    /** SO_TIMEOUT is a syscall: only touch it when the value changes (not once per event). */
    private void setSoTimeout(int ms) throws IOException {
        if (soTimeout != ms) {
            socket.setSoTimeout(ms);
            soTimeout = ms;
        }
    }

    /**
     * One ESL message: header block up to the empty line, then exactly
     * {@code Content-Length} <em>bytes</em> of body. A {@code text/event-plain} body is
     * parsed into the same map in one pass (its {@code Name: value} lines override the
     * outer headers, values stay URL-encoded); any other body is kept as {@code _body},
     * the whole message as {@code _raw}. Empty map on EOF.
     */
    private Map<String, String> readHeaders() throws IOException {
        Map<String, String> headers = new LinkedHashMap<>(64);
        int contentLength = 0;
        int off = 0; // relative to pos — survives compaction in fill()
        while (true) {
            int nl = indexOf(buf, pos + off, lim, NEWLINES);
            if (nl < 0) {
                if (fill() < 0) {
                    return headers;
                }
                continue;
            }
            int lineStart = pos + off;
            int lineEnd = nl > lineStart && buf[nl - 1] == '\r' ? nl - 1 : nl;
            off = nl + 1 - pos;
            if (lineEnd == lineStart) {
                break;
            }
            String[] kv = header(buf, lineStart, lineEnd);
            if (kv != null) {
                headers.put(kv[0], kv[1]);
                if ("Content-Length".equalsIgnoreCase(kv[0])) {
                    try {
                        contentLength = Integer.parseInt(kv[1]);
                    } catch (NumberFormatException ignored) {
                        contentLength = 0;
                    }
                }
            }
        }
        int headerLen = off;
        while (lim - pos < headerLen + contentLength) {
            if (fill() < 0) {
                contentLength = Math.max(0, lim - pos - headerLen);
                break;
            }
        }
        int bodyStart = pos + headerLen;
        if ("text/event-plain".equals(headers.get("Content-Type"))) {
            parseLines(buf, bodyStart, bodyStart + contentLength, headers);
        } else {
            if (contentLength > 0) {
                headers.put("_body", new String(buf, bodyStart, contentLength, StandardCharsets.UTF_8));
            }
            headers.put("_raw", new String(buf, pos, headerLen + contentLength, StandardCharsets.UTF_8));
        }
        pos += headerLen + contentLength;
        return headers;
    }

    /** Read more bytes after the unconsumed ones (compacting / growing first). */
    private int fill() throws IOException {
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, lim - pos);
            lim -= pos;
            pos = 0;
        }
        if (lim == buf.length) {
            buf = Arrays.copyOf(buf, buf.length * 2);
        }
        int n = in.read(buf, lim, buf.length - lim);
        if (n > 0) {
            lim += n;
        }
        return n;
    }

    /** Every {@code Name: value} line of {@code [from, to)} into {@code out}. */
    public static void parseLines(byte[] a, int from, int to, Map<String, String> out) {
        int lineStart = from;
        while (lineStart < to) {
            int nl = indexOf(a, lineStart, to, NEWLINES);
            int lineEnd = nl < 0 ? to : nl;
            int end = lineEnd > lineStart && a[lineEnd - 1] == '\r' ? lineEnd - 1 : lineEnd;
            String[] kv = header(a, lineStart, end);
            if (kv != null) {
                out.put(kv[0], kv[1]);
            }
            lineStart = lineEnd + 1;
        }
    }

    /** {@code name: value} → trimmed pair, or {@code null} without a colon at index > 0. */
    private static String[] header(byte[] a, int from, int to) {
        int colon = indexOf(a, from, to, COLONS);
        if (colon <= from) {
            return null;
        }
        int ns = from;
        int ne = colon;
        while (ns < ne && a[ns] <= ' ') {
            ns++;
        }
        while (ne > ns && a[ne - 1] <= ' ') {
            ne--;
        }
        int vs = colon + 1;
        int ve = to;
        while (vs < ve && a[vs] <= ' ') {
            vs++;
        }
        while (ve > vs && a[ve - 1] <= ' ') {
            ve--;
        }
        return new String[] {
                new String(a, ns, ne - ns, StandardCharsets.UTF_8),
                new String(a, vs, ve - vs, StandardCharsets.UTF_8)};
    }

    // ── SWAR byte search: 8 bytes per step in one 64-bit register ─────────

    private static final VarHandle LONGS =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long ONES = 0x0101010101010101L;
    private static final long HIGHS = 0x8080808080808080L;
    static final long NEWLINES = ONES * '\n';
    static final long COLONS = ONES * ':';

    /**
     * First index of the byte repeated in {@code pattern} within {@code [from, to)}, or -1.
     * Loads 8 bytes as a little-endian long, XORs with the pattern (matching bytes → 0)
     * and finds the lowest zero byte with {@code (x - 0x01…) & ~x & 0x80…}; the lowest
     * flagged byte is always a true match (borrows only corrupt higher bytes).
     */
    static int indexOf(byte[] a, int from, int to, long pattern) {
        int i = from;
        for (; i + Long.BYTES <= to; i += Long.BYTES) {
            long x = (long) LONGS.get(a, i) ^ pattern;
            long hit = (x - ONES) & ~x & HIGHS;
            if (hit != 0) {
                return i + (Long.numberOfTrailingZeros(hit) >>> 3);
            }
        }
        byte b = (byte) pattern;
        for (; i < to; i++) {
            if (a[i] == b) {
                return i;
            }
        }
        return -1;
    }

    private String readBodyOrHeaders() throws IOException {
        Map<String, String> h = readHeaders();
        if (h.containsKey("_body")) {
            return h.get("_body");
        }
        return h.getOrDefault("_raw", "");
    }

    @Override
    public void close() {
        live = false;
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }
}
