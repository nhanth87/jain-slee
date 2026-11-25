package com.microjainslee.ra.sipservlet.collab;

import gov.nist.javax.sip.header.To;
import gov.nist.javax.sip.message.SIPRequest;
import gov.nist.javax.sip.message.SIPResponse;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sip.message.Request;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RFC 3261 §17.1 client transactions for requests the RA sends over an unreliable
 * transport (UDP). Reliable transports (TCP/TLS/SCTP) retransmit below SIP, so only the
 * UDP timers live here:
 *
 * <ul>
 *   <li>INVITE (§17.1.1.2): Timer A retransmits at T1, 2·T1, 4·T1 … until any response;
 *       Timer B (64·T1) without one → timeout.</li>
 *   <li>non-INVITE (§17.1.2.2): Timer E retransmits at min(2ⁿ·T1, T2), at T2 once a
 *       provisional arrived; Timer F (64·T1) without a final response → timeout.</li>
 * </ul>
 * A timeout is reported to the RA, which hands the TU a locally generated 408 (§8.1.3.1).
 * ACK is not a transaction and is never tracked. Matching (§17.1.3): top Via branch +
 * CSeq method.
 *
 * <p>INVITE final non-2xx (§17.1.1.3): the transaction itself ACKs it — same top Via branch,
 * Request-URI and Route set as the INVITE, To from the response — and stays Completed for
 * Timer D (32 s over UDP, 0 over a reliable transport), re-sending that ACK for every
 * retransmission of the final response. Without it the UAS (FreeSWITCH 407/486/…) retransmits
 * the final response on Timer G until Timer H. INVITEs over reliable transports are tracked
 * for this ACK only (no retransmission timers).
 */
public final class ClientTransactions {

    private static final Logger LOG = LogManager.getLogger(ClientTransactions.class);

    public static final long T1_MILLIS = 500L;
    public static final long T2_MILLIS = 4_000L;
    /** Timer B / F. */
    public static final long TIMEOUT_MILLIS = 64 * T1_MILLIS;
    /** INVITE answered provisionally but never finally: forget it after Timer C (§16.6). */
    public static final long PROCEEDING_LINGER_MILLIS = 180_000L;
    /** Timer D for UDP (§17.1.1.2: at least 32 s). */
    public static final long TIMER_D_MILLIS = 32_000L;

    /** Re-sends recorded bytes. */
    @FunctionalInterface
    public interface Retransmitter {
        void retransmit(DialogRegistry.SentMessage sent);
    }

    /** Timer B / F fired with no (final) response. */
    @FunctionalInterface
    public interface TimeoutListener {
        void onTimeout(SIPRequest request, DialogRegistry.SentMessage sent);
    }

    private final class Tx {
        final String key;
        final SIPRequest request;
        final DialogRegistry.SentMessage sent;
        final boolean invite;
        volatile boolean proceeding;
        volatile boolean done;
        /** §17.1.1.3 ACK for a final non-2xx, re-sent on each retransmission of that response. */
        volatile DialogRegistry.SentMessage errorAck;
        volatile long interval = t1;
        volatile Timeout retransmitTimer;
        volatile Timeout timeoutTimer;

        Tx(String key, SIPRequest request, DialogRegistry.SentMessage sent) {
            this.key = key;
            this.request = request;
            this.sent = sent;
            this.invite = Request.INVITE.equals(request.getMethod());
        }

        void cancelTimers() {
            Timeout r = retransmitTimer;
            if (r != null) {
                r.cancel();
            }
            Timeout t = timeoutTimer;
            if (t != null) {
                t.cancel();
            }
        }
    }

    private final Map<String, Tx> transactions = new ConcurrentHashMap<>();
    private final Retransmitter retransmitter;
    private final TimeoutListener timeoutListener;
    private final HashedWheelTimer timer;
    private final AtomicLong retransmissions = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong errorAcks = new AtomicLong();
    private final long t1;
    private final long t2;
    private final long timeoutMillis;

    public ClientTransactions(Retransmitter retransmitter, TimeoutListener timeoutListener) {
        this(retransmitter, timeoutListener, T1_MILLIS, T2_MILLIS, TIMEOUT_MILLIS);
    }

    /** Scaled timers (tests); RFC defaults otherwise. */
    ClientTransactions(Retransmitter retransmitter, TimeoutListener timeoutListener,
                       long t1, long t2, long timeoutMillis) {
        this.t1 = t1;
        this.t2 = t2;
        this.timeoutMillis = timeoutMillis;
        this.retransmitter = retransmitter;
        this.timeoutListener = timeoutListener;
        this.timer = new HashedWheelTimer(r -> {
            Thread t = new Thread(r, "sip-ra-client-tx");
            t.setDaemon(true);
            return t;
        }, 10, TimeUnit.MILLISECONDS);
    }

    /** §17.1.3: top Via branch + CSeq method. */
    static String key(String branch, String cseqMethod) {
        return (branch == null ? "" : branch) + "|" + cseqMethod;
    }

    /** Track {@code request} just put on the wire as {@code sent}. */
    public void start(SIPRequest request, DialogRegistry.SentMessage sent) {
        if (request == null || sent == null || Request.ACK.equals(request.getMethod())
                || request.getTopmostVia() == null) {
            return;
        }
        boolean udp = "UDP".equalsIgnoreCase(sent.transport());
        if (!udp && !Request.INVITE.equals(request.getMethod())) {
            return;
        }
        String key = key(request.getTopmostVia().getBranch(), request.getMethod());
        Tx tx = new Tx(key, request, sent);
        Tx previous = transactions.put(key, tx);
        if (previous != null) {
            previous.done = true;
            previous.cancelTimers();
        }
        if (!udp) {
            // Reliable transport: no Timer A/B; kept only to ACK a final non-2xx (§17.1.1.3).
            tx.timeoutTimer = timer.newTimeout(t -> transactions.remove(tx.key, tx),
                    PROCEEDING_LINGER_MILLIS, TimeUnit.MILLISECONDS);
            return;
        }
        tx.retransmitTimer = timer.newTimeout(t -> retransmit(tx), t1, TimeUnit.MILLISECONDS);
        tx.timeoutTimer = timer.newTimeout(t -> timedOut(tx), timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** Feed every inbound response; stops / slows retransmission (§17.1.1.2, §17.1.2.2). */
    public void onResponse(SIPResponse response) {
        if (response == null || response.getTopmostVia() == null || response.getCSeq() == null) {
            return;
        }
        String key = key(response.getTopmostVia().getBranch(), response.getCSeq().getMethod());
        Tx tx = transactions.get(key);
        if (tx == null) {
            return;
        }
        int status = response.getStatusCode();
        if (tx.invite && status >= 300) {
            ackFinalNon2xx(tx, response);
            return;
        }
        if (status >= 200) {
            if (transactions.remove(key, tx)) {
                tx.done = true;
                tx.cancelTimers();
            }
            return;
        }
        if (tx.proceeding) {
            return;
        }
        tx.proceeding = true;
        if (tx.invite) {
            // Proceeding: no more Timer A, no Timer B (§17.1.1.2); the far end owns it now.
            tx.cancelTimers();
            tx.timeoutTimer = timer.newTimeout(t -> {
                if (transactions.remove(tx.key, tx)) {
                    tx.done = true;
                }
            }, PROCEEDING_LINGER_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /** §17.1.1.3: ACK the final non-2xx (first copy or a retransmission), then Timer D. */
    private void ackFinalNon2xx(Tx tx, SIPResponse response) {
        DialogRegistry.SentMessage ack = tx.errorAck;
        if (ack == null) {
            synchronized (tx) {
                ack = tx.errorAck;
                if (ack == null) {
                    tx.done = true;
                    tx.cancelTimers();
                    try {
                        SIPRequest errorAck = tx.request.createErrorAck((To) response.getTo());
                        ack = new DialogRegistry.SentMessage(
                                errorAck.encodeAsBytes(tx.sent.transport()), tx.sent.peer(), tx.sent.transport());
                    } catch (Exception ex) {
                        LOG.warn("[sip-client-tx] cannot build ACK for {} {}: {}",
                                response.getStatusCode(), tx.key, ex.toString());
                        transactions.remove(tx.key, tx);
                        return;
                    }
                    tx.errorAck = ack;
                    boolean udp = "UDP".equalsIgnoreCase(tx.sent.transport());
                    tx.timeoutTimer = timer.newTimeout(t -> transactions.remove(tx.key, tx),
                            udp ? TIMER_D_MILLIS : 0L, TimeUnit.MILLISECONDS);
                }
            }
        }
        try {
            retransmitter.retransmit(ack);
            errorAcks.incrementAndGet();
        } catch (RuntimeException ex) {
            LOG.warn("[sip-client-tx] ACK send failed {}: {}", tx.key, ex.toString());
        }
    }

    /** ACKs sent for final non-2xx responses to INVITE (first copies and retransmissions). */
    public long errorAcks() {
        return errorAcks.get();
    }

    private void retransmit(Tx tx) {
        if (tx.done || (tx.invite && tx.proceeding)) {
            return;
        }
        try {
            retransmitter.retransmit(tx.sent);
            retransmissions.incrementAndGet();
        } catch (RuntimeException ex) {
            LOG.warn("[sip-client-tx] retransmit failed {}: {}", tx.key, ex.toString());
        }
        long next;
        if (tx.invite) {
            next = tx.interval * 2;
        } else {
            next = tx.proceeding ? t2 : Math.min(tx.interval * 2, t2);
        }
        tx.interval = next;
        if (!tx.done) {
            tx.retransmitTimer = timer.newTimeout(t -> retransmit(tx), next, TimeUnit.MILLISECONDS);
        }
    }

    private void timedOut(Tx tx) {
        if (!transactions.remove(tx.key, tx)) {
            return;
        }
        tx.done = true;
        tx.cancelTimers();
        timeouts.incrementAndGet();
        LOG.debug("[sip-client-tx] Timer {} fired {}", tx.invite ? "B" : "F", tx.key);
        try {
            timeoutListener.onTimeout(tx.request, tx.sent);
        } catch (RuntimeException ex) {
            LOG.warn("[sip-client-tx] timeout handling failed {}: {}", tx.key, ex.toString());
        }
    }

    public long retransmissions() {
        return retransmissions.get();
    }

    public long timeouts() {
        return timeouts.get();
    }

    public int size() {
        return transactions.size();
    }

    public void stop() {
        transactions.values().forEach(tx -> {
            tx.done = true;
            tx.cancelTimers();
        });
        transactions.clear();
        timer.stop();
    }
}
