package com.microjainslee.ra.sipservlet.stun;

import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.ra.sipservlet.events.IceCandidateEvent;

import java.net.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Gathers ICE candidates (host, srflx) for a call. Signaling-only — does not
 * relay RTP and does <strong>not</strong> invent fake {@code typ relay}
 * placeholders (TURN ALLOCATE is UA/coturn responsibility; rtp_redirect is
 * enforced by the app SDP policy).
 */
public final class IceCandidateCollector {

    private final StunClient stunClient;
    private final boolean preferRelay;
    private RaBootstrapPort bootstrapPort;

    /** A single ICE candidate with its transport address and type. */
    public record Candidate(
        String foundation,
        int componentId,
        String transport,
        long priority,
        String address,
        int port,
        String type
    ) {}

    public IceCandidateCollector(StunClient stunClient) {
        this(stunClient, false);
    }

    public IceCandidateCollector(StunClient stunClient, String turnServer, int turnPort,
                                 boolean preferRelay) {
        // turnServer/port retained in signature for SipRaConfig wiring; unused until
        // real RFC 5766 ALLOCATE is implemented (do not advertise fake relay).
        this(stunClient, preferRelay);
    }

    public IceCandidateCollector(StunClient stunClient, boolean preferRelay) {
        this.stunClient = stunClient;
        this.preferRelay = preferRelay;
    }

    public void setBootstrapPort(RaBootstrapPort bp) {
        this.bootstrapPort = bp;
    }

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
            if (preferRelay) {
                // Prefer srflx over host when relay is required but not yet allocated
                // by this RA (UA SDP must still carry typ relay for app policy).
                list.sort(Comparator.comparingInt(IceCandidateCollector::typeRank));
            }
            return list;
        });
    }

    public void fireCandidates(String callId, List<Candidate> candidates) {
        if (bootstrapPort != null) {
            bootstrapPort.fireEvent(
                new IceCandidateEvent(callId, candidates),
                bootstrapPort.createActivityHandle(callId), null);
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
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address) {
                        // component-id 1 = RTP (RFC 8445); port filled by media stack
                        result.add(new Candidate(
                            "host-" + addr.getHostAddress(),
                            1, "UDP", hostPriority(),
                            addr.getHostAddress(), 0, "host"));
                    }
                }
            }
        } catch (SocketException ignored) {
            // Non-fatal
        }
        return result;
    }

    private static int typeRank(Candidate c) {
        return switch (c.type() == null ? "" : c.type().toLowerCase(Locale.ROOT)) {
            case "relay" -> 0;
            case "srflx" -> 1;
            default -> 2;
        };
    }

    private static long hostPriority() {
        return (126L << 24) | (65535L << 8) | 255;
    }

    private static long srflxPriority() {
        return (100L << 24) | (65535L << 8) | 255;
    }
}
