/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.diameter.transport;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.diameter.DiameterRaConfig;
import com.microjainslee.ra.diameter.collab.DiameterOutboundSender;
import com.microjainslee.ra.diameter.command.DiameterCommand;
import com.microjainslee.ra.diameter.command.SendDiameterAnswer;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;
import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.avp.DiameterAvpEncoder;
import com.microjainslee.ra.diameter.events.DiameterEvent;
import com.mobius.software.common.dal.timers.WorkerPool;
import com.mobius.software.telco.protocols.diameter.ApplicationIDs;
import com.mobius.software.telco.protocols.diameter.AsyncCallback;
import com.mobius.software.telco.protocols.diameter.DiameterLink;
import com.mobius.software.telco.protocols.diameter.DiameterStack;
import com.mobius.software.telco.protocols.diameter.NetworkListener;
import com.mobius.software.telco.protocols.diameter.PeerStateEnum;
import com.mobius.software.telco.protocols.diameter.commands.DiameterMessage;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;
import com.mobius.software.telco.protocols.diameter.impl.DiameterStackImpl;
import com.mobius.software.telco.protocols.diameter.primitives.common.VendorSpecificApplicationId;
import io.netty.buffer.Unpooled;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jdiameter.api.Avp;
import org.jdiameter.api.AvpSet;
import org.jdiameter.api.Message;
import org.jdiameter.client.api.IMessage;
import org.jdiameter.client.impl.parser.MessageParser;
import org.restcomm.cluster.UUIDGenerator;

/**
 * Mobius Corsac stack as the Diameter <em>connection</em> plane (TCP and/or SCTP).
 * Application bytes become SLEE events; SBBs answer via {@link DiameterCommand}.
 *
 * <p>One Corsac link per configured peer ({@link DiameterRaConfig#effectivePeers()}): an
 * HSS serves the MME (S6a) and the CSCFs (Cx), a PCRF the PGW-C (Gx) and the P-CSCF (Rx).
 * An answer leaves on the link its request arrived on; a request goes to the peer whose
 * Destination-Host matches, else the peer serving its application, else its realm.</p>
 */
public final class CorsacDiameterTransport implements DiameterTransport, DiameterOutboundSender {

    public static final String LINK_ID = "diameter-ra";

    private static final Logger LOG = LogManager.getLogger(CorsacDiameterTransport.class);

    private final DiameterRaConfig config;
    private final DiameterTransportCallbacks callbacks;
    private final MessageParser parser = new MessageParser();
    private final AtomicBoolean started = new AtomicBoolean(false);

    private WorkerPool workerPool;
    private DiameterStack stack;
    private volatile RaBootstrapPort bootstrap;
    /** Link id per configured peer, in configuration order (first = {@link #LINK_ID}). */
    private volatile List<Route> routes = List.of();
    /** Inbound request (hop-by-hop, end-to-end) → link it arrived on, for its answer. */
    private final java.util.Map<Long, String> answerLinks = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<Long, String>(1024, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Long, String> eldest) {
                    return size() > MAX_PENDING_ANSWERS;
                }
            });

    /** Unanswered inbound requests remembered for answer routing. */
    static final int MAX_PENDING_ANSWERS = 65_536;

    /** A configured peer and the Corsac link that serves it. */
    record Route(String linkId, DiameterRaConfig.Peer peer) {}

    public CorsacDiameterTransport(DiameterRaConfig config, DiameterTransportCallbacks callbacks) {
        this.config = Objects.requireNonNull(config);
        this.callbacks = Objects.requireNonNull(callbacks);
    }

    public void setBootstrap(RaBootstrapPort bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public String protocol() {
        return config.sctpEnabled() ? "SCTP" : "TCP";
    }

    /** At least one peer link is OPEN (CER/CEA done). */
    public boolean linkUp() {
        for (Route r : routes) {
            if (linkUp(r.linkId())) {
                return true;
            }
        }
        return false;
    }

    public boolean linkUp(String linkId) {
        DiameterLink link = link(linkId);
        return link != null && link.isUp() && link.getPeerState() == PeerStateEnum.OPEN;
    }

    public boolean linkConnected() {
        for (Route r : routes) {
            DiameterLink link = link(r.linkId());
            if (link != null && link.isConnected()) {
                return true;
            }
        }
        return false;
    }

    /** Peer host:port → OPEN, per configured peer (status / admin). */
    public java.util.Map<String, Boolean> peerStates() {
        java.util.Map<String, Boolean> out = new java.util.LinkedHashMap<>();
        for (Route r : routes) {
            out.put(r.peer().host() + ":" + r.peer().port(), linkUp(r.linkId()));
        }
        return out;
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            workerPool = new WorkerPool("diameter-ra");
            workerPool.start(4);
            stack = new DiameterStackImpl(
                    getClass().getClassLoader(),
                    new UUIDGenerator(),
                    workerPool,
                    4,
                    config.originHost(),
                    config.productName(),
                    config.vendorId(),
                    10L,
                    120_000L,
                    60_000L,
                    2_000L,
                    0L,
                    0L);
            List<DiameterRaConfig.Peer> peers = config.effectivePeers();
            long servers = peers.stream().filter(DiameterRaConfig.Peer::server).count();
            if (servers > 1) {
                // One listen socket per port: let the other side of extra peers dial us.
                throw new IllegalArgumentException("at most one server-role Diameter peer per RA (port "
                        + config.port() + "); configure the others as client");
            }
            List<Route> built = new java.util.ArrayList<>();
            for (int i = 0; i < peers.size(); i++) {
                DiameterRaConfig.Peer peer = peers.get(i);
                String linkId = i == 0 ? LINK_ID : LINK_ID + "-" + (i + 1);
                startLink(linkId, peer);
                built.add(new Route(linkId, peer));
            }
            routes = List.copyOf(built);
        } catch (Exception e) {
            started.set(false);
            throw new IllegalStateException("corsac Diameter stack failed to start", e);
        }
    }

    private void startLink(String linkId, DiameterRaConfig.Peer peer) throws Exception {
        InetAddress remote = InetAddress.getByName(
                peer.host() == null || peer.host().isBlank() ? "127.0.0.1" : peer.host());
        boolean sctp = config.sctpEnabled();
        boolean server = peer.server();
        InetAddress local = localBindAddress(config.host(), server, remote);
        int localPort = server ? config.port() : 0;
        // Server role: the peer dials from an ephemeral port — 0 = any source port.
        int remotePort = server ? Math.max(0, peer.port()) : (peer.port() > 0 ? peer.port() : config.port());
        stack.getNetworkManager().addLink(
                linkId,
                remote,
                remotePort,
                local,
                localPort,
                server,
                sctp,
                config.originHost(),
                config.realm(),
                peer.destinationHost(),
                peer.destinationRealm(),
                Boolean.FALSE);
        // Command registration is per Diameter application package. The common
        // commands (CER/DWR/DPR, app 0) are already registered in the stack
        // constructor — re-registering commons here throws
        // "request 271 is already registered for application 0". Order matters:
        // impl package FIRST — the parser jar-scan loads concrete *Impl classes,
        // then the compiler fallback must NOT re-load the API interfaces (their
        // (code,isRequest) tuples collide with what the impls just registered).
        // Each app keeps its own (code,isRequest) space, so S6a / CxDx / Gx / Rx /
        // CreditControl never collide with one another.
        //
        // Packages are derived from LOADED classes: Package.getPackage(String)
        // returns null for packages no class of has been loaded from yet, and
        // DiameterLinkImpl NPEs on a null provider package (authApplicationPackages).
        registerApp(linkId, ApplicationIDs.S6A,
                com.mobius.software.telco.protocols.diameter.commands.s6a.AuthenticationInformationRequest.class,
                com.mobius.software.telco.protocols.diameter.impl.commands.s6a.AuthenticationInformationRequestImpl.class);
        registerApp(linkId, ApplicationIDs.CX_DX,
                com.mobius.software.telco.protocols.diameter.commands.cxdx.UserAuthorizationRequest.class,
                com.mobius.software.telco.protocols.diameter.impl.commands.cxdx.UserAuthorizationRequestImpl.class);
        registerApp(linkId, ApplicationIDs.GX,
                com.mobius.software.telco.protocols.diameter.commands.gx.CreditControlRequest.class,
                com.mobius.software.telco.protocols.diameter.impl.commands.gx.CreditControlRequestImpl.class);
        registerApp(linkId, ApplicationIDs.RX,
                com.mobius.software.telco.protocols.diameter.commands.rx.AARequest.class,
                com.mobius.software.telco.protocols.diameter.impl.commands.rx.AARequestImpl.class);
        registerApp(linkId, ApplicationIDs.CREDIT_CONTROL,
                com.mobius.software.telco.protocols.diameter.commands.creditcontrol.CreditControlRequest.class,
                com.mobius.software.telco.protocols.diameter.impl.commands.creditcontrol.CreditControlRequestImpl.class);
        // Corsac hands every message to every registered network listener: keep only this
        // link's own messages, or each request is delivered (and answered) once per link.
        stack.getNetworkManager().addNetworkListener(linkId, (message, fromLink, callback) -> {
            if (linkId.equals(fromLink)) {
                onCorsacMessage(message, fromLink, callback);
            }
        });
        stack.getNetworkManager().startLink(linkId);
        LOG.info("[diameter-ra] corsac {} link={} role={} local={}:{} peer={}:{} dest={}/{} apps={} (LISTEN/dial ≠ peer UP)",
                protocol(), linkId, peer.role(), local.getHostAddress(), localPort,
                remote.getHostAddress(), remotePort, peer.destinationHost(), peer.destinationRealm(),
                peer.applicationIds());
    }

    @Override
    public void stop() {
        started.set(false);
        if (stack != null) {
            stack.stop();
            stack = null;
        }
        if (workerPool != null) {
            workerPool = null;
        }
        LOG.info("[diameter-ra] corsac stopped");
    }

    @Override
    public void send(DiameterCommand cmd) {
        String linkId = route(cmd, routes, answerLinks, this::linkUp);
        DiameterLink link = linkId == null ? null : link(linkId);
        if (link == null || !link.isConnected()) {
            LOG.warn("[diameter-ra] corsac send dropped — no live link for {} (link={})",
                    cmd.getClass().getSimpleName(), linkId);
            return;
        }
        try {
            IMessage msg = toJdiameter(cmd);
            ByteBuffer encoded = parser.encodeMessage(msg);
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            link.sendEncodedMessage(Unpooled.wrappedBuffer(bytes), noop());
        } catch (Exception e) {
            LOG.warn("[diameter-ra] corsac encode/send failed", e);
        }
    }

    private void onCorsacMessage(DiameterMessage message, String linkId, AsyncCallback callback) {
        if (message == null) {
            return;
        }
        if (CorsacEventBridge.isBaseProtocol(message)) {
            if (linkUp()) {
                callbacks.onPeerConnected(linkId);
            }
            return;
        }
        if (linkUp()) {
            callbacks.onPeerConnected(linkId);
        }
        if (message instanceof com.mobius.software.telco.protocols.diameter.commands.DiameterRequest req) {
            answerLinks.put(answerKey(req.getHopByHopIdentifier(), req.getEndToEndIdentifier()), linkId);
        }
        DiameterEvent event = CorsacEventBridge.toEvent(message, wireAvps(message));
        RaBootstrapPort port = bootstrap;
        if (event == null) {
            LOG.warn("[diameter-ra] {} on {}: no Diameter command definition — not delivered",
                    message.getClass().getName(), linkId);
            return;
        }
        if (port == null) {
            LOG.warn("[diameter-ra] {} on {}: RA not bound to a SLEE — not delivered",
                    message.getClass().getSimpleName(), linkId);
            return;
        }
        String activity = event.sessionId() == null || event.sessionId().isBlank()
                ? linkId
                : event.sessionId();
        ActivityHandle handle = port.createActivityHandle(activity);
        port.fireEvent((SleeEvent) event, handle, (Address) () -> activity);
    }

    /**
     * Register one Diameter application: {@code implMarker} selects the package of
     * concrete command classes (registered first); {@code apiMarker} selects the
     * provider package of API interfaces.
     */
    private void registerApp(String linkId, int applicationId, Class<?> apiMarker, Class<?> implMarker)
            throws Exception {
        stack.getNetworkManager().registerApplication(
                linkId,
                List.<VendorSpecificApplicationId>of(),
                List.of((long) applicationId),
                List.of(),
                apiMarker.getPackage(),
                implMarker.getPackage());
    }

    /** Every AVP as received: re-encode the Corsac message and parse it generically. */
    private AvpSet wireAvps(DiameterMessage message) {
        DiameterStack s = stack;
        if (s == null) {
            return null;
        }
        try {
            io.netty.buffer.ByteBuf buf = s.getGlobalParser().encode(message);
            byte[] bytes;
            try {
                bytes = new byte[buf.readableBytes()];
                buf.getBytes(buf.readerIndex(), bytes);
            } finally {
                buf.release();
            }
            return parser.createMessage(framed(bytes)).getAvps();
        } catch (Exception e) {
            LOG.warn("[diameter-ra] AVP re-parse failed for {}: {}", message.getClass().getSimpleName(), e.toString());
            return null;
        }
    }

    /**
     * One Diameter message whose header Message-Length (bytes 1..3) equals its size: the
     * re-encoded Corsac buffer can disagree with its own header, which the jdiameter parser
     * rejects ("Wrong length of data"). Longer buffer → cut to the header length; shorter →
     * the header takes the real size.
     */
    static byte[] framed(byte[] bytes) {
        if (bytes.length < 20) {
            return bytes;
        }
        int declared = ((bytes[1] & 0xff) << 16) | ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
        if (declared == bytes.length) {
            return bytes;
        }
        LOG.debug("[diameter-ra] re-encoded message length header={} actual={}", declared, bytes.length);
        if (declared >= 20 && declared < bytes.length) {
            return java.util.Arrays.copyOf(bytes, declared);
        }
        byte[] fixed = bytes.clone();
        fixed[1] = (byte) (bytes.length >>> 16);
        fixed[2] = (byte) (bytes.length >>> 8);
        fixed[3] = (byte) bytes.length;
        return fixed;
    }

    private DiameterLink link(String linkId) {
        return stack == null || linkId == null ? null : stack.getNetworkManager().getLink(linkId);
    }

    static long answerKey(Long hopByHop, Long endToEnd) {
        long h = hopByHop == null ? 0L : hopByHop & 0xffffffffL;
        long e = endToEnd == null ? 0L : endToEnd & 0xffffffffL;
        return (h << 32) | e;
    }

    /**
     * Link for an outbound command. Answer: the link its request came in on. Request: the
     * peer whose Destination-Host matches, else one serving its application (open links
     * first), else one in its Destination-Realm, else the first open link, else the first.
     */
    static String route(DiameterCommand cmd, List<Route> routes, java.util.Map<Long, String> answerLinks,
                        java.util.function.Predicate<String> up) {
        if (routes.isEmpty()) {
            return null;
        }
        if (cmd instanceof SendDiameterAnswer ans) {
            String linkId = answerLinks.remove(answerKey(ans.hopByHopId(), ans.endToEndId()));
            if (linkId != null) {
                return linkId;
            }
        }
        if (cmd instanceof SendDiameterRequest req) {
            if (req.destinationHost() != null && !req.destinationHost().isBlank()) {
                for (Route r : routes) {
                    if (req.destinationHost().equalsIgnoreCase(r.peer().destinationHost())) {
                        return r.linkId();
                    }
                }
            }
            String byApp = first(routes, r -> r.peer().applicationIds().contains(req.applicationId()), up);
            if (byApp != null) {
                return byApp;
            }
            if (req.destinationRealm() != null && !req.destinationRealm().isBlank()) {
                String byRealm = first(routes,
                        r -> req.destinationRealm().equalsIgnoreCase(r.peer().destinationRealm()), up);
                if (byRealm != null) {
                    return byRealm;
                }
            }
        }
        for (Route r : routes) {
            if (up.test(r.linkId())) {
                return r.linkId();
            }
        }
        return routes.get(0).linkId();
    }

    /** First matching route, preferring an open link. */
    private static String first(List<Route> routes, java.util.function.Predicate<Route> match,
                                java.util.function.Predicate<String> up) {
        String any = null;
        for (Route r : routes) {
            if (match.test(r)) {
                if (up.test(r.linkId())) {
                    return r.linkId();
                }
                if (any == null) {
                    any = r.linkId();
                }
            }
        }
        return any;
    }

    // package-private for the wire-shape test
    IMessage toJdiameter(DiameterCommand cmd) {
        if (cmd instanceof SendDiameterRequest req) {
            IMessage msg = parser.createEmptyMessage(req.commandCode(), req.applicationId());
            msg.setRequest(true);
            addAvps(msg, req.sessionId(), java.util.Map.of());
            addOrigin(msg);
            addAvps(msg, null, req.avps());
            encodeStructured(msg.getAvps(), req.avpsStructured());
            if (req.destinationHost() != null) {
                msg.getAvps().addAvp(Avp.DESTINATION_HOST, req.destinationHost(), true, false, true);
            }
            if (req.destinationRealm() != null) {
                msg.getAvps().addAvp(Avp.DESTINATION_REALM, req.destinationRealm(), true, false, true);
            }
            return msg;
        }
        if (cmd instanceof SendDiameterAnswer ans) {
            IMessage msg = parser.createEmptyMessage(ans.commandCode(), ans.applicationId());
            msg.setRequest(false);
            msg.setHopByHopIdentifier(ans.hopByHopId());
            msg.setEndToEndIdentifier(ans.endToEndId());
            // RFC 6733 §8.8: <Session-Id> is the fixed head of every session message — first, before
            // Result-Code (freeDiameter rejects the answer otherwise: "FIXED_HEAD AVP … position").
            addAvps(msg, ans.sessionId(), java.util.Map.of());
            // RFC 6733 §6.2: every answer carries Origin-Host / Origin-Realm (M).
            addOrigin(msg);
            // 0 is no Diameter result: the outcome travels as Experimental-Result (3GPP apps).
            if (ans.resultCode() > 0) {
                msg.getAvps().addAvp(Avp.RESULT_CODE, ans.resultCode(), true);
            }
            addAvps(msg, null, ans.avps());
            encodeStructured(msg.getAvps(), ans.avpsStructured());
            return msg;
        }
        throw new IllegalArgumentException("unsupported command " + cmd);
    }

    private static void encodeStructured(AvpSet set, List<DiameterAvp> avps) {
        if (avps == null || avps.isEmpty()) {
            return;
        }
        DiameterAvpEncoder.encode(avps, set);
    }

    /** Origin-Host / Origin-Realm of this node, unless the caller put its own (RFC 6733 §6.3 / §6.4). */
    private void addOrigin(IMessage msg) {
        if (msg.getAvps().getAvp(Avp.ORIGIN_HOST) == null && config.originHost() != null
                && !config.originHost().isBlank()) {
            msg.getAvps().addAvp(Avp.ORIGIN_HOST, config.originHost(), true, false, true);
        }
        if (msg.getAvps().getAvp(Avp.ORIGIN_REALM) == null && config.realm() != null && !config.realm().isBlank()) {
            msg.getAvps().addAvp(Avp.ORIGIN_REALM, config.realm(), true, false, true);
        }
    }

    private static void addAvps(IMessage msg, String sessionId, java.util.Map<Integer, String> avps) {
        if (sessionId != null && !sessionId.isBlank()) {
            msg.getAvps().addAvp(Avp.SESSION_ID, sessionId, true, false, true);
        }
        if (avps == null) {
            return;
        }
        avps.forEach((code, value) -> {
            if (value != null) {
                msg.getAvps().addAvp(code, value, false);
            }
        });
    }

    /**
     * Local bind address of the link. A wildcard / blank host means: a server listens on
     * every interface; a client binds the source address the kernel routes towards the
     * peer. (Mapping the wildcard to 127.0.0.1 left a server reachable from its own host
     * only and a client unable to dial any non-loopback peer — e.g. container to
     * container — so S6a/Cx never reached CER.)
     */
    static InetAddress localBindAddress(String host, boolean server, InetAddress remote)
            throws java.net.UnknownHostException {
        boolean wildcard = host == null || host.isBlank() || "0.0.0.0".equals(host.trim());
        if (!wildcard) {
            return InetAddress.getByName(host.trim());
        }
        if (server) {
            return InetAddress.getByName("0.0.0.0");
        }
        return routeSource(remote);
    }

    /** Source address for {@code remote} from the routing table (no packet is sent). */
    static InetAddress routeSource(InetAddress remote) {
        try (java.net.DatagramSocket probe = new java.net.DatagramSocket()) {
            probe.connect(remote, 9);
            InetAddress a = probe.getLocalAddress();
            if (a != null && !a.isAnyLocalAddress()) {
                return a;
            }
        } catch (java.io.IOException ignored) {
            // unroutable peer: loopback below, the link reports the dial failure
        }
        return InetAddress.getLoopbackAddress();
    }

    private static AsyncCallback noop() {
        return new AsyncCallback() {
            @Override public void onSuccess() { }
            @Override public void onError(DiameterException e) { }
        };
    }
}
