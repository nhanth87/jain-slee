package com.microjainslee.ra.sipservlet.collab;

import com.microjainslee.ra.sipservlet.events.SipEvent;

import java.net.InetSocketAddress;

/**
 * Classifies a parsed SIP message into a typed {@link SipEvent}.
 * <p>Receives a JAIN-SIP {@code javax.sip.message.Message} (NIST
 * {@code SIPMessage} implements it) and returns the appropriate
 * sealed event subtype, or {@code null} to silently drop.
 */
@FunctionalInterface
public interface SipEventClassifier {
    /** Classify a parsed SIP message.
     * @param msg    JAIN-SIP Message (usually NIST SIPMessage instance)
     * @param callId pre-extracted Call-ID value
     * @return typed event, or {@code null} to drop
     */
    SipEvent classify(Object msg, String callId);

    /**
     * Classify with the transport source (REGISTER/INVITE NAT flow).
     * Default delegates to {@link #classify(Object, String)} so existing
     * implementations keep compiling.
     */
    default SipEvent classify(Object msg, String callId, InetSocketAddress peer) {
        return classify(msg, callId, peer, null);
    }

    /** Same as {@link #classify(Object, String, InetSocketAddress)} plus socket transport. */
    default SipEvent classify(Object msg, String callId, InetSocketAddress peer, String transport) {
        return classify(msg, callId);
    }
}
