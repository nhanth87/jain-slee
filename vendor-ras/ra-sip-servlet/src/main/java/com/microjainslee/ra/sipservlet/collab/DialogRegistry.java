/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.sipservlet.collab;

import com.microjainslee.api.ActivityHandle;
import gov.nist.javax.sip.message.SIPMessage;
import gov.nist.javax.sip.message.SIPRequest;
import gov.nist.javax.sip.message.SIPResponse;
import gov.nist.javax.sip.parser.StringMsgParser;

import javax.sip.header.ContactHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.ToHeader;
import javax.sip.message.Request;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

        void seedLastRequest(SIPRequest request) {
            if (request != null) {
                this.lastRequest = request;
                touchActivity();
            }
        }

        void seedLastResponse(SIPResponse response) {
            if (response != null) {
                this.lastResponse = response;
                touchActivity();
            }
        }
    }

    /**
     * Portable DialogRegistry meta for ISPN / sticky session-meta (ADR 0002)
     * and Elisa {@code DialogCheckpoint} dual-write.
     *
     * <p>Peers + cseq + dialog URI/tag essentials (not full NIST stack objects).
     * {@link DialogRegistry#restorePortable} rebuilds synthetic {@code lastRequest}
     * / far-leg {@code lastResponse} when URI+tag fields are present so lab
     * SendResponse/SendBye can resume after S-CSCF failover (not Clearwater Chronos).
     */
    public record PortableDialogMeta(
            String callId,
            String peerHost,
            int peerPort,
            String transport,
            String remotePeerHost,
            int remotePeerPort,
            String remoteTransport,
            long cseq,
            String fromUri,
            String fromTag,
            String toUri,
            String toTag,
            String contactUri,
            String remoteContactUri,
            String method,
            String farFromUri,
            String farFromTag
    ) implements java.io.Serializable {
        private static final long serialVersionUID = 3L;

        /** Compat ctor — peers + cseq only (pre-wire-essentials). */
        public PortableDialogMeta(
                String callId,
                String peerHost,
                int peerPort,
                String transport,
                String remotePeerHost,
                int remotePeerPort,
                String remoteTransport,
                long cseq) {
            this(callId, peerHost, peerPort, transport, remotePeerHost, remotePeerPort,
                    remoteTransport, cseq, null, null, null, null, null, null, null, null, null);
        }

        public boolean hasWireEssentials() {
            return fromUri != null && !fromUri.isBlank() && fromTag != null && !fromTag.isBlank();
        }

        public boolean hasFarWireEssentials() {
            String fTag = farFromTag != null ? farFromTag : fromTag;
            return remoteContactUri != null && !remoteContactUri.isBlank()
                    && toTag != null && !toTag.isBlank()
                    && fTag != null && !fTag.isBlank();
        }

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
            putIfPresent(attrs, "fromUri", fromUri);
            putIfPresent(attrs, "fromTag", fromTag);
            putIfPresent(attrs, "toUri", toUri);
            putIfPresent(attrs, "toTag", toTag);
            putIfPresent(attrs, "contactUri", contactUri);
            putIfPresent(attrs, "remoteContactUri", remoteContactUri);
            putIfPresent(attrs, "method", method);
            putIfPresent(attrs, "farFromUri", farFromUri);
            putIfPresent(attrs, "farFromTag", farFromTag);
            return attrs;
        }

        private static void putIfPresent(Map<String, String> attrs, String key, String value) {
            if (value != null && !value.isBlank()) {
                attrs.put(key, value);
            }
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
            String fromUri = attrs.get("fromUri");
            String fromTag = attrs.get("fromTag");
            if (peerHost == null && remoteHost == null && (fromUri == null || fromUri.isBlank())) {
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
                    cseq,
                    fromUri,
                    fromTag,
                    attrs.get("toUri"),
                    attrs.get("toTag"),
                    attrs.get("contactUri"),
                    attrs.get("remoteContactUri"),
                    attrs.get("method"),
                    attrs.get("farFromUri"),
                    attrs.get("farFromTag"));
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
        String fromUri = null;
        String fromTag = null;
        String toUri = null;
        String toTag = null;
        String contactUri = null;
        String method = null;
        SIPRequest lastReq = d.lastRequest();
        if (lastReq != null) {
            method = lastReq.getMethod();
            FromHeader from = (FromHeader) lastReq.getHeader(FromHeader.NAME);
            ToHeader to = (ToHeader) lastReq.getHeader(ToHeader.NAME);
            ContactHeader contact = (ContactHeader) lastReq.getHeader(ContactHeader.NAME);
            if (from != null && from.getAddress() != null) {
                fromUri = from.getAddress().getURI().toString();
                fromTag = from.getTag();
            }
            if (to != null && to.getAddress() != null) {
                toUri = to.getAddress().getURI().toString();
                toTag = to.getTag();
            }
            if (contact != null && contact.getAddress() != null) {
                contactUri = contact.getAddress().getURI().toString();
            }
        }
        String remoteContactUri = null;
        String farFromUri = null;
        String farFromTag = null;
        String farToTag = null;
        SIPResponse lastResp = d.lastResponse();
        if (lastResp != null) {
            ContactHeader rc = (ContactHeader) lastResp.getHeader(ContactHeader.NAME);
            if (rc != null && rc.getAddress() != null) {
                remoteContactUri = rc.getAddress().getURI().toString();
            }
            FromHeader respFrom = (FromHeader) lastResp.getHeader(FromHeader.NAME);
            if (respFrom != null && respFrom.getAddress() != null) {
                farFromUri = respFrom.getAddress().getURI().toString();
                farFromTag = respFrom.getTag();
            }
            ToHeader respTo = (ToHeader) lastResp.getHeader(ToHeader.NAME);
            if (respTo != null && respTo.getTag() != null) {
                farToTag = respTo.getTag();
            }
        }
        String exportToTag = farToTag != null ? farToTag : toTag;
        return new PortableDialogMeta(
                callId,
                hostOf(peer),
                peer != null ? peer.getPort() : 0,
                d.transport(),
                hostOf(remote),
                remote != null ? remote.getPort() : 0,
                d.remoteTransport(),
                d.cseq(),
                fromUri,
                fromTag,
                toUri,
                exportToTag,
                contactUri,
                remoteContactUri,
                method != null ? method : Request.INVITE,
                farFromUri,
                farFromTag);
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
     * Restore peers + cseq from portable meta; when URI/tag essentials are present,
     * rebuild synthetic lastRequest (and far lastResponse) for lab SendResponse/SendBye.
     * Live wire objects are never overwritten if already present.
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
        seedWireFromPortable(dialog, meta);
        return dialog;
    }

    /**
     * Lab-grade synthetic wire rebuild for mid-dialog Send* after failover.
     * Not a full NIST dialog SM — enough for SendResponse from INVITE and
     * SendBye toward UA / far trunk when tags+contacts were checkpointed.
     */
    void seedWireFromPortable(Dialog dialog, PortableDialogMeta meta) {
        if (dialog == null || meta == null) {
            return;
        }
        try {
            if (dialog.lastRequest() == null && meta.hasWireEssentials()) {
                SIPRequest req = buildSyntheticRequest(meta);
                if (req != null) {
                    dialog.seedLastRequest(req);
                }
            }
            if (dialog.lastResponse() == null && meta.hasFarWireEssentials()) {
                SIPResponse resp = buildSyntheticFarResponse(meta);
                if (resp != null) {
                    dialog.seedLastResponse(resp);
                }
            }
        } catch (Exception ignored) {
            // Best-effort; inbound wire after failover remains the honest fallback.
        }
    }

    private static SIPRequest buildSyntheticRequest(PortableDialogMeta meta) throws Exception {
        String method = meta.method() != null && !meta.method().isBlank() ? meta.method() : Request.INVITE;
        long cseq = meta.cseq() > 0 ? meta.cseq() : 1L;
        String contact = bareUri(meta.contactUri() != null ? meta.contactUri() : meta.fromUri());
        String fromUri = bareUri(meta.fromUri());
        String toUri = bareUri(meta.toUri() != null ? meta.toUri() : "sip:unknown@invalid");
        String transport = meta.transport() != null ? meta.transport() : "UDP";
        String peerHost = meta.peerHost() != null ? meta.peerHost() : "127.0.0.1";
        int peerPort = meta.peerPort() > 0 ? meta.peerPort() : 5060;
        String fromTag = meta.fromTag() != null ? ";tag=" + meta.fromTag() : "";
        String toTag = meta.toTag() != null ? ";tag=" + meta.toTag() : "";
        String raw = method + " " + contact + " SIP/2.0\r\n"
                + "Via: SIP/2.0/" + transport + " " + peerHost + ":" + peerPort
                + ";branch=z9hG4bK-ha-restore\r\n"
                + "From: <" + fromUri + ">" + fromTag + "\r\n"
                + "To: <" + toUri + ">" + toTag + "\r\n"
                + "Call-ID: " + meta.callId() + "\r\n"
                + "CSeq: " + cseq + " " + method + "\r\n"
                + "Contact: <" + contact + ">\r\n"
                + "Max-Forwards: 70\r\n"
                + "Content-Length: 0\r\n\r\n";
        SIPMessage msg = new StringMsgParser().parseSIPMessage(
                raw.getBytes(StandardCharsets.US_ASCII), true, false, null);
        return msg instanceof SIPRequest req ? req : null;
    }

    private static SIPResponse buildSyntheticFarResponse(PortableDialogMeta meta) throws Exception {
        // Far UAC INVITE 200: From=local UAC tag toward trunk, To=remote with toTag.
        String fromUri = bareUri(meta.farFromUri() != null ? meta.farFromUri() : meta.fromUri());
        String fromTag = meta.farFromTag() != null ? meta.farFromTag() : meta.fromTag();
        String toUri = bareUri(meta.toUri() != null ? meta.toUri() : meta.remoteContactUri());
        String remoteContact = bareUri(meta.remoteContactUri());
        String transport = meta.remoteTransport() != null ? meta.remoteTransport()
                : (meta.transport() != null ? meta.transport() : "UDP");
        String host = meta.remotePeerHost() != null ? meta.remotePeerHost() : "127.0.0.1";
        int port = meta.remotePeerPort() > 0 ? meta.remotePeerPort() : 5060;
        long cseq = meta.cseq() > 0 ? meta.cseq() : 1L;
        String raw = "SIP/2.0 200 OK\r\n"
                + "Via: SIP/2.0/" + transport + " " + host + ":" + port
                + ";branch=z9hG4bK-ha-far\r\n"
                + "From: <" + fromUri + ">;tag=" + fromTag + "\r\n"
                + "To: <" + toUri + ">;tag=" + meta.toTag() + "\r\n"
                + "Call-ID: " + meta.callId() + "\r\n"
                + "CSeq: " + cseq + " INVITE\r\n"
                + "Contact: <" + remoteContact + ">\r\n"
                + "Content-Length: 0\r\n\r\n";
        SIPMessage msg = new StringMsgParser().parseSIPMessage(
                raw.getBytes(StandardCharsets.US_ASCII), true, false, null);
        return msg instanceof SIPResponse resp ? resp : null;
    }

    private static String bareUri(String uri) {
        if (uri == null) {
            return "sip:unknown@invalid";
        }
        String s = uri.trim();
        if (s.startsWith("<") && s.endsWith(">") && s.length() > 2) {
            s = s.substring(1, s.length() - 1);
        }
        int semi = s.indexOf(';');
        if (semi > 0) {
            s = s.substring(0, semi);
        }
        return s;
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
