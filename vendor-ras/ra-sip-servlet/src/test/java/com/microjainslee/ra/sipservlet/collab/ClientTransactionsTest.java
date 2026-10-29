package com.microjainslee.ra.sipservlet.collab;

import gov.nist.javax.sip.message.SIPRequest;
import gov.nist.javax.sip.message.SIPResponse;
import gov.nist.javax.sip.parser.StringMsgParser;
import org.junit.After;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** RFC 3261 §17.1.1.2 / §17.1.2.2 UDP client transaction timers (T1 scaled to 20 ms). */
public class ClientTransactionsTest {

    static final long T1 = 20;
    static final long T2 = 160;
    static final long TIMEOUT = 64 * T1;

    final AtomicInteger resent = new AtomicInteger();
    final List<SIPRequest> timedOut = new CopyOnWriteArrayList<>();
    final ClientTransactions ctx = new ClientTransactions(
            sent -> resent.incrementAndGet(), (req, sent) -> timedOut.add(req), T1, T2, TIMEOUT);

    @After
    public void stop() {
        ctx.stop();
    }

    @Test
    public void inviteRetransmitsUntilProvisionalThenNeverTimesOut() throws Exception {
        SIPRequest invite = request("INVITE", "z9hG4bK-a");
        ctx.start(invite, sent("UDP"));
        Thread.sleep(T1 * 4); // A at T1, 2T1 (3T1 total) → 2 copies
        int before = resent.get();
        assertTrue("Timer A retransmits, got " + before, before >= 1 && before <= 3);

        ctx.onResponse(response(invite, 100));
        Thread.sleep(T1); // a copy already on the wheel tick may still go out
        int proceeding = resent.get();
        Thread.sleep(TIMEOUT + T1 * 5);
        assertEquals("no retransmission once Proceeding", proceeding, resent.get());
        assertTrue("no Timer B after a provisional", timedOut.isEmpty());

        ctx.onResponse(response(invite, 200));
        assertEquals(0, ctx.size());
    }

    @Test
    public void inviteWithoutAnyResponseTimesOutAfterTimerB() throws Exception {
        SIPRequest invite = request("INVITE", "z9hG4bK-b");
        ctx.start(invite, sent("UDP"));
        Thread.sleep(TIMEOUT + T1 * 5);
        assertEquals(1, timedOut.size());
        assertEquals(invite, timedOut.get(0));
        // doubling from T1 within 64·T1: T1,2,4,8,16,32 → 6 copies
        assertTrue("A doubles: ~6 copies, got " + resent.get(), resent.get() >= 5 && resent.get() <= 6);
        assertEquals(0, ctx.size());
        assertEquals(1, ctx.timeouts());
    }

    @Test
    public void nonInviteCapsAtT2AndStopsOnFinal() throws Exception {
        SIPRequest bye = request("BYE", "z9hG4bK-c");
        ctx.start(bye, sent("UDP"));
        Thread.sleep(TIMEOUT + T1 * 5);
        // E: 20,40,80,160,160,… within 1280 ms → 4 + (1280-300)/160 ≈ 10
        assertTrue("Timer E capped at T2, got " + resent.get(), resent.get() >= 8 && resent.get() <= 11);
        assertEquals("Timer F", 1, timedOut.size());

        SIPRequest options = request("OPTIONS", "z9hG4bK-d");
        ctx.start(options, sent("UDP"));
        ctx.onResponse(response(options, 200));
        int after = resent.get();
        Thread.sleep(T1 * 6);
        assertEquals("final response ends the transaction", after, resent.get());
        assertEquals(1, timedOut.size());
    }

    @Test
    public void ackAndReliableTransportsAreNotTracked() throws Exception {
        ctx.start(request("ACK", "z9hG4bK-e"), sent("UDP"));
        ctx.start(request("INVITE", "z9hG4bK-f"), sent("TCP"));
        assertEquals(0, ctx.size());
        Thread.sleep(T1 * 4);
        assertEquals(0, resent.get());
    }

    @Test
    public void responseMatchesOnBranchAndCseqMethod() throws Exception {
        SIPRequest invite = request("INVITE", "z9hG4bK-g");
        ctx.start(invite, sent("UDP"));
        // CANCEL shares the branch but is its own transaction (§9.1).
        ctx.onResponse(response(request("CANCEL", "z9hG4bK-g"), 200));
        assertEquals(1, ctx.size());
        ctx.onResponse(response(invite, 486));
        assertEquals(0, ctx.size());
    }

    static DialogRegistry.SentMessage sent(String transport) {
        return new DialogRegistry.SentMessage(new byte[] {1}, new InetSocketAddress("127.0.0.1", 5062), transport);
    }

    static SIPRequest request(String method, String branch) throws Exception {
        String raw = method + " sip:1000@127.0.0.1:5062 SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP 127.0.0.1:5060;branch=" + branch + "\r\n"
                + "Max-Forwards: 70\r\n"
                + "From: <sip:alice@ims.lab>;tag=a1\r\n"
                + "To: <sip:1000@ims.lab>\r\n"
                + "Call-ID: ctx-" + branch + "\r\n"
                + "CSeq: 1 " + method + "\r\n"
                + "Content-Length: 0\r\n\r\n";
        return (SIPRequest) new StringMsgParser()
                .parseSIPMessage(raw.getBytes(StandardCharsets.US_ASCII), true, false, null);
    }

    static SIPResponse response(SIPRequest request, int status) {
        return request.createResponse(status);
    }
}
