/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.sipservlet.collab;

import com.microjainslee.api.ActivityHandle;
import gov.nist.javax.sip.message.SIPRequest;
import gov.nist.javax.sip.message.SIPResponse;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-Call-ID dialog state for the outbound path and idle sweeper.
 *
 * <p>Two peers for SIP-edge / trunk hops (not a full B2BUA SM):
 * <ul>
 *   <li>{@code peer} — reply path (UA); updated only on inbound <em>requests</em></li>
 *   <li>{@code remotePeer} — far trunk (e.g. FreeSWITCH); set when {@code SendInvite} is sent</li>
 * </ul>
 * Inbound <em>responses</em> must not overwrite {@code peer}, or 200 INVITE would
 * be sent back to the trunk instead of the UA.
 */
public final class DialogRegistry {

    /** Mutable per-dialog state. */
    public static final class Dialog {
        public final String callId;
        public final ActivityHandle handle;
        private volatile SIPRequest lastRequest;
        private volatile SIPResponse lastResponse;
        /** UA / reply peer — where SendResponse goes. */
        private volatile InetSocketAddress peer;
        private volatile String transport;
        /** Far trunk peer — where SendBye / SendAck toward callee go. */
        private volatile InetSocketAddress remotePeer;
        private volatile String remoteTransport;
        private volatile long lastActivityMillis;
        private final AtomicLong cseq = new AtomicLong();

        Dialog(String callId, ActivityHandle handle) {
            this.callId = callId;
            this.handle = handle;
            this.lastActivityMillis = System.currentTimeMillis();
        }

        public SIPRequest lastRequest() { return lastRequest; }
        public SIPResponse lastResponse() { return lastResponse; }
        public InetSocketAddress peer() { return peer; }
        public String transport() { return transport; }
        public InetSocketAddress remotePeer() { return remotePeer; }
        public String remoteTransport() {
            return remoteTransport != null ? remoteTransport : transport;
        }
        public long lastActivityMillis() { return lastActivityMillis; }

        public long nextCseq() {
            return cseq.incrementAndGet();
        }

        void touchActivity() {
            this.lastActivityMillis = System.currentTimeMillis();
        }

        void setReplyPeer(InetSocketAddress peer, String transport) {
            this.peer = peer;
            this.transport = transport;
            touchActivity();
        }

        void setRemotePeer(InetSocketAddress peer, String transport) {
            this.remotePeer = peer;
            this.remoteTransport = transport;
            touchActivity();
        }
    }

    private final Map<String, Dialog> dialogs = new ConcurrentHashMap<>();

    /** Create-or-update the dialog for an inbound message. */
    public Dialog recordInbound(String callId, ActivityHandle handle, Object sipMessage,
                                InetSocketAddress peer, String transport) {
        Dialog dialog = dialogs.computeIfAbsent(callId, id -> new Dialog(id, handle));
        if (sipMessage instanceof SIPRequest req) {
            dialog.lastRequest = req;
            long cseq = req.getCSeq() != null ? req.getCSeq().getSeqNumber() : 0L;
            dialog.cseq.updateAndGet(cur -> Math.max(cur, cseq));
            // Only requests update the reply peer (UA). Responses from the trunk
            // must not steal the return path.
            dialog.setReplyPeer(peer, transport);
        } else if (sipMessage instanceof SIPResponse resp) {
            dialog.lastResponse = resp;
            dialog.touchActivity();
        }
        return dialog;
    }

    /** Record far-leg peer after outbound INVITE (trunk). */
    public void recordRemotePeer(String callId, InetSocketAddress peer, String transport) {
        Dialog dialog = dialogs.get(callId);
        if (dialog != null && peer != null) {
            dialog.setRemotePeer(peer, transport);
        }
    }

    public Dialog find(String callId) {
        return dialogs.get(callId);
    }

    public Dialog remove(String callId) {
        return dialogs.remove(callId);
    }

    public int size() {
        return dialogs.size();
    }

    public void clear() {
        dialogs.clear();
    }

    /** Remove and return every dialog idle for longer than {@code idleMillis}. */
    public List<Dialog> expireIdle(long idleMillis) {
        long cutoff = System.currentTimeMillis() - idleMillis;
        List<Dialog> expired = new ArrayList<>();
        for (Map.Entry<String, Dialog> entry : dialogs.entrySet()) {
            if (entry.getValue().lastActivityMillis < cutoff) {
                Dialog removed = dialogs.remove(entry.getKey());
                if (removed != null) {
                    expired.add(removed);
                }
            }
        }
        return expired;
    }
}
