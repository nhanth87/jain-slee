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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jdiameter.api.Avp;
import org.jdiameter.api.Message;
import org.jdiameter.client.api.IMessage;
import org.jdiameter.client.impl.parser.MessageParser;
import org.restcomm.cluster.UUIDGenerator;

/**
 * Mobius Corsac stack as the Diameter <em>connection</em> plane (TCP and/or SCTP).
 * Application bytes become SLEE events; SBBs answer via {@link DiameterCommand}.
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

    public boolean linkUp() {
        DiameterLink link = link();
        return link != null && link.isUp() && link.getPeerState() == PeerStateEnum.OPEN;
    }

    public boolean linkConnected() {
        DiameterLink link = link();
        return link != null && link.isConnected();
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
            InetAddress local = InetAddress.getByName(listenHost());
            InetAddress remote = InetAddress.getByName(peerHost());
            boolean sctp = config.sctpEnabled();
            boolean server = !"client".equalsIgnoreCase(config.peerRole());
            int localPort = server ? config.port() : 0;
            stack.getNetworkManager().addLink(
                    LINK_ID,
                    remote,
                    peerPort(),
                    local,
                    localPort,
                    server,
                    sctp,
                    config.originHost(),
                    config.realm(),
                    config.destinationHost(),
                    config.destinationRealm(),
                    Boolean.FALSE);
            List<Long> auth = Arrays.asList(
                    (long) ApplicationIDs.S6A,
                    (long) ApplicationIDs.CX_DX,
                    (long) ApplicationIDs.GX,
                    (long) ApplicationIDs.CREDIT_CONTROL);
            stack.getNetworkManager().registerApplication(
                    LINK_ID,
                    List.<VendorSpecificApplicationId>of(),
                    auth,
                    List.of(),
                    Package.getPackage("com.mobius.software.telco.protocols.diameter.commands.commons"),
                    Package.getPackage("com.mobius.software.telco.protocols.diameter.impl.commands.common"));
            stack.getNetworkManager().addNetworkListener(LINK_ID, this::onCorsacMessage);
            stack.getNetworkManager().startLink(LINK_ID);
            LOG.info("[diameter-ra] corsac {} role={} local={}:{} peer={}:{} (LISTEN/dial ≠ peer UP)",
                    protocol(), config.peerRole(), local.getHostAddress(), localPort,
                    remote.getHostAddress(), peerPort());
        } catch (Exception e) {
            started.set(false);
            throw new IllegalStateException("corsac Diameter stack failed to start", e);
        }
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
        DiameterLink link = link();
        if (link == null || !link.isConnected()) {
            LOG.warn("[diameter-ra] corsac send dropped — no live link");
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
        DiameterEvent event = CorsacEventBridge.toEvent(message);
        RaBootstrapPort port = bootstrap;
        if (event == null || port == null) {
            return;
        }
        String activity = event.sessionId() == null || event.sessionId().isBlank()
                ? linkId
                : event.sessionId();
        ActivityHandle handle = port.createActivityHandle(activity);
        port.fireEvent((SleeEvent) event, handle, (Address) () -> activity);
    }

    private DiameterLink link() {
        return stack == null ? null : stack.getNetworkManager().getLink(LINK_ID);
    }

    private IMessage toJdiameter(DiameterCommand cmd) {
        if (cmd instanceof SendDiameterRequest req) {
            IMessage msg = parser.createEmptyMessage(req.commandCode(), req.applicationId());
            msg.setRequest(true);
            addAvps(msg, req.sessionId(), req.avps());
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
            msg.getAvps().addAvp(Avp.RESULT_CODE, ans.resultCode(), true);
            addAvps(msg, ans.sessionId(), ans.avps());
            return msg;
        }
        throw new IllegalArgumentException("unsupported command " + cmd);
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

    private String listenHost() {
        String host = config.host();
        if (host == null || host.isBlank() || "0.0.0.0".equals(host)) {
            return "127.0.0.1";
        }
        return host;
    }

    private String peerHost() {
        String host = config.peerHost();
        return host == null || host.isBlank() ? "127.0.0.1" : host;
    }

    private int peerPort() {
        return config.peerPort() > 0 ? config.peerPort() : config.port();
    }

    private static AsyncCallback noop() {
        return new AsyncCallback() {
            @Override public void onSuccess() { }
            @Override public void onError(DiameterException e) { }
        };
    }
}
