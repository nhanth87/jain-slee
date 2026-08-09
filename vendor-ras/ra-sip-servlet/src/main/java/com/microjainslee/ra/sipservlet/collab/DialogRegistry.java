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
        public long cseq() { return cseq.get(); }

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

        void seedCseq(long value) {
            if (value > 0) {
                cseq.updateAndGet(cur -> Math.max(cur, value));
            }
        }
    }

    /**
     * Portable DialogRegistry meta for ISPN / sticky session-meta (ADR 0002).
     * Peers + cseq only — no SIPRequest/SIPResponse stack objects.
     * Mid-dialog SendResponse still needs {@code lastRequest} from wire rebuild;
     * peer priming lets the failover node accept sticky inbound and resume after
     * the first recovered request/response.
     */
    public record PortableDialogMeta(
            String callId,
            String peerHost,
            int peerPort,
            String transport,
            String remotePeerHost,
            int remotePeerPort,
            String remoteTransport,
            long cseq
    ) implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        public Map<String, String> toAttrs() {
            Map<String, String> attrs = new java.util.LinkedHashMap<>();
            if (peerHost != null && peerPort > 0) {
                attrs.put("peer", peerHost + ":" + peerPort);
                attrs.put("peerHost", peerHost);
                attrs.put("peerPort", Integer.toString(peerPort));
            }
            if (transport != null) {
                attrs.put("transport", transport);
            }
            if (remotePeerHost != null && remotePeerPort > 0) {
                attrs.put("remotePeer", remotePeerHost + ":" + remotePeerPort);
                attrs.put("remotePeerHost", remotePeerHost);
                attrs.put("remotePeerPort", Integer.toString(remotePeerPort));
            }
            if (remoteTransport != null) {
                attrs.put("remoteTransport", remoteTransport);
            }
            if (cseq > 0) {
                attrs.put("cseq", Long.toString(cseq));
            }
            return attrs;
        }

        public static PortableDialogMeta fromAttrs(String callId, Map<String, String> attrs) {
            if (callId == null || attrs == null || attrs.isEmpty()) {
                return null;
            }
            String peerHost = attrs.get("peerHost");
            int peerPort = parsePort(attrs.get("peerPort"), 0);
            if ((peerHost == null || peerPort <= 0) && attrs.get("peer") != null) {
                HostPort hp = parseHostPort(attrs.get("peer"));
                peerHost = hp.host;
                peerPort = hp.port;
            }
            String remoteHost = attrs.get("remotePeerHost");
            int remotePort = parsePort(attrs.get("remotePeerPort"), 0);
            if ((remoteHost == null || remotePort <= 0) && attrs.get("remotePeer") != null) {
                HostPort hp = parseHostPort(attrs.get("remotePeer"));
                remoteHost = hp.host;
                remotePort = hp.port;
            }
            long cseq = 0L;
            try {
                String c = attrs.get("cseq");
                if (c != null) {
                    cseq = Long.parseLong(c.trim());
                }
            } catch (NumberFormatException ignored) {
                cseq = 0L;
            }
            if (peerHost == null && remoteHost == null) {
                return null;
            }
            return new PortableDialogMeta(
                    callId,
                    peerHost,
                    peerPort,
                    attrs.get("transport"),
                    remoteHost,
                    remotePort,
                    attrs.getOrDefault("remoteTransport", attrs.get("transport")),
                    cseq);
        }

        private static int parsePort(String s, int def) {
            if (s == null || s.isBlank()) {
                return def;
            }
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }

        private static HostPort parseHostPort(String s) {
            if (s == null || s.isBlank()) {
                return new HostPort(null, 0);
            }
            int colon = s.lastIndexOf(':');
            if (colon <= 0 || colon >= s.length() - 1) {
                return new HostPort(s, 0);
            }
            try {
                return new HostPort(s.substring(0, colon), Integer.parseInt(s.substring(colon + 1)));
            } catch (NumberFormatException e) {
                return new HostPort(s, 0);
            }
        }

        private record HostPort(String host, int port) {}
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

    public boolean contains(String callId) {
        return callId != null && dialogs.containsKey(callId);
    }

    /** Export portable meta for ISPN sticky session-meta / failover priming. */
    public PortableDialogMeta exportPortable(String callId) {
        Dialog d = find(callId);
        if (d == null) {
            return null;
        }
        InetSocketAddress peer = d.peer();
        InetSocketAddress remote = d.remotePeer();
        return new PortableDialogMeta(
                callId,
                hostOf(peer),
                peer != null ? peer.getPort() : 0,
                d.transport(),
                hostOf(remote),
                remote != null ? remote.getPort() : 0,
                d.remoteTransport(),
                d.cseq());
    }

    private static String hostOf(InetSocketAddress addr) {
        if (addr == null) {
            return null;
        }
        if (addr.getAddress() != null) {
            return addr.getAddress().getHostAddress();
        }
        String host = addr.getHostString();
        return host == null || host.isBlank() ? null : host;
    }

    /**
     * Restore peers + cseq from portable meta without SIP message objects.
     * Does not invent lastRequest/lastResponse — SendResponse still needs wire
     * rebuild (R4 honesty). Returns the dialog entry (created or updated).
     */
    public Dialog restorePortable(PortableDialogMeta meta, ActivityHandle handle) {
        if (meta == null || meta.callId() == null || meta.callId().isBlank()) {
            return null;
        }
        ActivityHandle h = handle != null ? handle : () -> meta.callId();
        Dialog dialog = dialogs.computeIfAbsent(meta.callId(), id -> new Dialog(id, h));
        if (meta.peerHost() != null && meta.peerPort() > 0) {
            dialog.setReplyPeer(new InetSocketAddress(meta.peerHost(), meta.peerPort()), meta.transport());
        }
        if (meta.remotePeerHost() != null && meta.remotePeerPort() > 0) {
            dialog.setRemotePeer(
                    new InetSocketAddress(meta.remotePeerHost(), meta.remotePeerPort()),
                    meta.remoteTransport() != null ? meta.remoteTransport() : meta.transport());
        }
        dialog.seedCseq(meta.cseq());
        return dialog;
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
