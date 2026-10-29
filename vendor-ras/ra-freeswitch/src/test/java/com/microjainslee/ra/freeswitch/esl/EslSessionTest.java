package com.microjainslee.ra.freeswitch.esl;

import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Byte-level ESL framing + the SWAR byte search behind it. */
class EslSessionTest {

    @Test
    void swarIndexOfMatchesNaiveSearchAtEveryAlignment() {
        Random rnd = new Random(7);
        for (int round = 0; round < 2_000; round++) {
            byte[] a = new byte[rnd.nextInt(80)];
            for (int i = 0; i < a.length; i++) {
                // dense alphabet around the targets: '\n' (0x0a), ':' (0x3a), 0x0b, 0x80+
                int r = rnd.nextInt(8);
                a[i] = (byte) (r == 0 ? '\n' : r == 1 ? ':' : r == 2 ? 0x0b : r == 3 ? 0x8a : 'a' + rnd.nextInt(26));
            }
            int from = a.length == 0 ? 0 : rnd.nextInt(a.length + 1);
            int to = from + (a.length - from == 0 ? 0 : rnd.nextInt(a.length - from + 1));
            assertEquals(naive(a, from, to, (byte) '\n'), EslSession.indexOf(a, from, to, EslSession.NEWLINES));
            assertEquals(naive(a, from, to, (byte) ':'), EslSession.indexOf(a, from, to, EslSession.COLONS));
        }
    }

    private static int naive(byte[] a, int from, int to, byte b) {
        for (int i = from; i < to; i++) {
            if (a[i] == b) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void eventPlainBodyIsParsedFromBytesEvenWhenSplitAcrossReads() throws Exception {
        String body = "Event-Name: CHANNEL_ANSWER\n"
                + "Unique-ID: 6f1c\n"
                + "Caller-Caller-ID-Name: Nguy%E1%BB%85n\n"
                + "variable_note: trường hợp\n"   // raw multi-byte UTF-8: bytes ≠ chars
                + "Channel-State: CS_EXECUTE\n\n";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        byte[] event = concat(("Content-Length: " + bodyBytes.length + "\n"
                + "Content-Type: text/event-plain\n\n").getBytes(StandardCharsets.US_ASCII), bodyBytes);
        String apiBody = "+OK 42 channels\n";
        byte[] api = ("Content-Type: api/response\nContent-Length: " + apiBody.length() + "\n\n" + apiBody)
                .getBytes(StandardCharsets.US_ASCII);

        try (ServerSocket server = new ServerSocket(0)) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread fs = Thread.ofVirtual().start(() -> {
                try (Socket c = server.accept(); OutputStream out = c.getOutputStream()) {
                    out.write("Content-Type: auth/request\n\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    readCommand(c);
                    out.write("Content-Type: command/reply\nReply-Text: +OK accepted\n\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    readCommand(c); // event plain …
                    out.write("Content-Type: command/reply\nReply-Text: +OK event listener enabled plain\n\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    // the event in 7-byte TCP segments: every boundary lands mid-line somewhere
                    for (int i = 0; i < event.length; i += 7) {
                        out.write(event, i, Math.min(7, event.length - i));
                        out.flush();
                    }
                    out.write(api);
                    out.flush();
                } catch (Throwable t) {
                    failure.set(t);
                }
            });

            try (EslSession session = new EslSession("127.0.0.1", server.getLocalPort(), "ClueCon", 2_000, 2_000)) {
                session.connect();
                assertTrue(session.live());
                session.subscribe("CHANNEL_ANSWER");

                Map<String, String> ev = session.readNextMessage();
                assertEquals("CHANNEL_ANSWER", ev.get("Event-Name"));
                assertEquals("6f1c", ev.get("Unique-ID"));
                assertEquals("Nguy%E1%BB%85n", ev.get("Caller-Caller-ID-Name"), "values stay URL-encoded, as before");
                assertEquals("trường hợp", ev.get("variable_note"));
                assertEquals("CS_EXECUTE", ev.get("Channel-State"));
                assertFalse(ev.containsKey("_raw") || ev.containsKey("_body"), "event-plain carries no _raw/_body copy");

                Map<String, String> reply = session.readNextMessage();
                assertEquals("api/response", reply.get("Content-Type"));
                assertEquals(apiBody, reply.get("_body"));
                assertTrue(reply.get("_raw").endsWith(apiBody));
            }
            fs.join(2_000);
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
        }
    }

    /** Consume one client command (terminated by a blank line). */
    private static void readCommand(Socket c) throws Exception {
        var in = c.getInputStream();
        int prev = -1;
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n' && prev == '\n') {
                return;
            }
            prev = b;
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
