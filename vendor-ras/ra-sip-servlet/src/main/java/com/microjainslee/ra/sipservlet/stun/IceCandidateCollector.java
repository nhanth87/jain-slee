package com.microjainslee.ra.sipservlet.stun;

import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.ra.sipservlet.events.IceCandidateEvent;

import java.net.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Gathers ICE candidates (host, srflx) for a call. Signaling-only — does not
 * relay RTP. When TURN is configured, a relay placeholder is added so SBBs
 * can prefer the firewall path ({@code rtp_redirect} / prefer-relay); full
 * RFC 5766 ALLOCATE remains a future enhancement (UA/coturn usually supplies
 * real relay candidates in SDP).
 */
public final class IceCandidateCollector {

    private final StunClient stunClient;
    private final String turnServer;
    private final int turnPort;
    private final boolean preferRelay;
    private RaBootstrapPort bootstrapPort;

    /** A single ICE candidate with its transport address and type. */
    public record Candidate(
        String foundation,
        int componentId,
        String transport,      // "UDP" or "TCP"
        long priority,
        String address,
        int port,
        String type            // "host", "srflx", "relay"
    ) {}

    public IceCandidateCollector(StunClient stunClient) {
        this(stunClient, null, 0, false);
    }

    public IceCandidateCollector(StunClient stunClient, String turnServer, int turnPort,
                                 boolean preferRelay) {
        this.stunClient = stunClient;
        this.turnServer = turnServer;
        this.turnPort = turnPort > 0 ? turnPort : 3478;
        this.preferRelay = preferRelay;
    }

    public void setBootstrapPort(RaBootstrapPort bp) {
        this.bootstrapPort = bp;
    }

    /**
     * Gather candidates: host + srflx (STUN) + optional TURN relay placeholder.
     * When {@code preferRelay}, relay-type entries are sorted first for SBBs.
     */
    public CompletableFuture<List<Candidate>> gatherAll() {
        List<Candidate> candidates = new ArrayList<>(gatherHostCandidates());

        CompletableFuture<List<Candidate>> afterStun;
        if (stunClient != null) {
            afterStun = stunClient.sendBindingRequest().thenApply(stunResult -> {
                if (stunResult.isValid()) {
                    candidates.add(new Candidate(
                        "srflx-" + stunResult.publicAddress(),
                        1, "UDP", srflxPriority(),
                        stunResult.publicAddress(), stunResult.publicPort(), "srflx"));
                }
                return candidates;
            });
        } else {
            afterStun = CompletableFuture.completedFuture(candidates);
        }

        return afterStun.thenApply(list -> {
            addTurnRelayPlaceholder(list);
            if (preferRelay) {
                list.sort(Comparator.comparingInt(IceCandidateCollector::typeRank));
            }
            return list;
        });
    }

    /**
     * Fire IceCandidateEvent through the SLEE EventRouter.
     */
    public void fireCandidates(String callId, List<Candidate> candidates) {
        if (bootstrapPort != null) {
            bootstrapPort.fireEvent(
                new IceCandidateEvent(callId, candidates),
                bootstrapPort.createActivityHandle(callId), null);
        }
    }

    private void addTurnRelayPlaceholder(List<Candidate> candidates) {
        if (turnServer == null || turnServer.isBlank()) {
            return;
        }
        try {
            InetAddress resolved = InetAddress.getByName(turnServer);
            candidates.add(new Candidate(
                    "relay-" + resolved.getHostAddress(),
                    1, "UDP", relayPriority(),
                    resolved.getHostAddress(), turnPort, "relay"));
        } catch (UnknownHostException e) {
            // Non-fatal: leave host/srflx only
        }
    }

    private List<Candidate> gatherHostCandidates() {
        List<Candidate> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                int compId = 1;
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address) {
                        result.add(new Candidate(
                            "host-" + addr.getHostAddress(),
                            compId++, "UDP", hostPriority(),
                            addr.getHostAddress(), 0, "host"));
                    }
                }
            }
        } catch (SocketException ignored) {
            // Non-fatal: return whatever we gathered
        }
        return result;
    }

    /** Lower rank = preferred when preferRelay. */
    private static int typeRank(Candidate c) {
        return switch (c.type() == null ? "" : c.type().toLowerCase(Locale.ROOT)) {
            case "relay" -> 0;
            case "srflx" -> 1;
            default -> 2;
        };
    }

    // RFC 5245: type pref = 126 (host), 100 (srflx), 0 (relay)
    private static long hostPriority() {
        return (126L << 24) | (65535L << 8) | 255;
    }

    private static long srflxPriority() {
        return (100L << 24) | (65535L << 8) | 255;
    }

    private static long relayPriority() {
        return (0L << 24) | (65535L << 8) | 255;
    }
}
