package com.microjainslee.ra.freeswitch.esl;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
    private BufferedReader in;
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
        socket.setSoTimeout(readTimeoutMs);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
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
        socket.setSoTimeout(0);
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

    private Map<String, String> readHeaders() throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        StringBuilder raw = new StringBuilder();
        String line;
        int contentLength = 0;
        while ((line = in.readLine()) != null) {
            raw.append(line).append('\n');
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                headers.put(name, value);
                if ("Content-Length".equalsIgnoreCase(name)) {
                    try {
                        contentLength = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        contentLength = 0;
                    }
                }
            }
        }
        if (contentLength > 0) {
            char[] buf = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(buf, read, contentLength - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            String body = new String(buf, 0, read);
            headers.put("_body", body);
            raw.append(body);
        }
        headers.put("_raw", raw.toString());
        return headers;
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
