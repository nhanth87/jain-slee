package com.example.helloworld.quarkus.sbbs;

import com.example.helloworld.quarkus.http.HttpReply;
import com.example.helloworld.quarkus.http.MonitorHandler;
import com.example.helloworld.quarkus.profile.HelloWorldProfileManager;
import com.example.helloworld.quarkus.profile.SessionProfile;
import com.example.helloworld.quarkus.telemetry.EndpointHitStore;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.core.ProfileAttachment;
import com.microjainslee.ra.httpserver.command.HttpServerCommand;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * HTTP gateway SBB — monitor surface first, else Hello World backed by a
 * {@link SessionProfile} <b>Profile CMP</b> row (keyed by HTTP session id).
 *
 * <h2>Phase 3 — ProfileAttachment + session checkpoint</h2>
 * <ul>
 *   <li>Each HTTP request updates the {@link SessionProfile} via the
 *       {@link HelloWorldProfileManager}. Profile fields are written through
 *       {@link com.microjainslee.api.ProfileAccessorInvoker#setValue}, which
 *       hooks the C3 undo-log when an active event-delivery transaction is
 *       present.</li>
 *   <li>On {@link #sbbPassivate()}, {@link ProfileAttachment#checkpoint} is
 *       called to persist a JSON snapshot of session state. Contract C9:
 *       failure is logged at ERROR and a {@link ProfileAttachment.CheckpointException}
 *       is thrown — never silently swallowed.</li>
 *   <li>On the <em>next request for the same session key</em>, the SBB calls
 *       {@link HelloWorldProfileManager#getOrCreateSession(String)} which
 *       returns the same CMP row (the profile survived entity death) and the
 *       hit-counter is restored from {@code checkpointJson}.</li>
 * </ul>
 *
 * <pre>
 *  request(sessionId) → SBB entity A → writes SessionProfile[sessionId]
 *  sbbPassivate       → attachment.checkpoint(sessionId, json)  ← C9
 *  entity A discarded → profile row survives in ProfileFacility
 *  request(same id)   → SBB entity B → getOrCreateSession → same CMP row
 *                     → restoreCheckpoint → hits continue from saved value
 * </pre>
 *
 * <p><b>TODO(Phase3 SBB CMP):</b> store {@code boundProfileKey} in SBB CMP
 * (via {@code CmpBackedSbb}) so {@link #sbbActivate()} can restore state
 * without waiting for the first request. Currently the key is heap-only and
 * restored lazily on the next event delivery.
 */
public final class HelloWorldSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(HelloWorldSbb.class);

    private final MonitorHandler monitor;   // nullable — only when no monitor surface
    private final HelloWorldProfileManager profiles;
    private final EndpointHitStore endpointHits;
    /**
     * Phase 3 — checkpoint helper; never null when the bootstrap wired it correctly.
     * Encapsulates C9 contract: checkpoint failures log ERROR + raise alarm.
     */
    private final ProfileAttachment attachment;

    /** Injected by the container at activation; matches {@code RaEndpointPort.getRaName()}. */
    @InjectRa(name = "http-server-ra")
    private volatile RaCommandPort http;

    private volatile SbbLocalObject self;
    /**
     * Last profile key touched by this entity (heap-only — dies with entity).
     * TODO(Phase3 SBB CMP): persist this in SBB CMP so sbbActivate() can restore checkpoint.
     */
    private volatile String boundProfileKey;

    public HelloWorldSbb(MonitorHandler monitor, HelloWorldProfileManager profiles,
                         EndpointHitStore endpointHits, ProfileAttachment attachment) {
        this.monitor = monitor;
        this.profiles = profiles;
        this.endpointHits = endpointHits;
        this.attachment = attachment;
    }

    public void bindSelf(SbbLocalObject self) {
        this.self = self;
    }

    @Override
    public void sbbCreate() { }

    /**
     * Phase 3 — restore checkpoint on entity activation.
     *
     * <p>Currently the profile key is heap-only (dies with the entity), so
     * on a fresh entity activation we don't have the key until the first
     * request arrives and calls {@code hello()}. Restoration is therefore
     * lazy — the next {@code onEvent} will call
     * {@link HelloWorldProfileManager#getOrCreateSession(String)} and reload
     * the profile row, which already carries the last {@code checkpointJson}
     * written by the previous entity's {@link #sbbPassivate()}.
     *
     * <p>TODO(Phase3 SBB CMP): once {@code boundProfileKey} is persisted via
     * SBB CMP, call {@link ProfileAttachment#restoreCheckpoint(String, String)} here.
     */
    @Override
    public void sbbActivate() {
        // Lazy restore — see sbbActivate javadoc for Phase3 SBB CMP TODO.
    }

    /**
     * Phase 3 — checkpoint session state before entity passivation.
     *
     * <p>Uses {@link ProfileAttachment#checkpoint(String, String, String)} so that
     * Contract C9 (ERROR log + alarm on failure) is guaranteed without the SBB
     * having to replicate the error-handling logic.
     *
     * <p>Note: this executes <em>outside</em> an event delivery, so there is no
     * active {@link com.microjainslee.core.SbbTransactionContext} and the C3
     * undo-log is not engaged (writes here are auto-committed per C3.3).
     */
    @Override
    public void sbbPassivate() {
        String key = boundProfileKey;
        if (key == null) {
            return;
        }
        try {
            SessionProfile session = profiles.getSession(key).orElse(null);
            if (session == null) {
                return;
            }
            String snapshot = buildCheckpointJson(session);
            attachment.checkpoint(SessionProfile.TABLE_NAME, key, snapshot);
            LOG.info("[HelloWorld] passivate checkpoint written: profileKey={}", key);
        } catch (ProfileAttachment.CheckpointException ce) {
            // C9: ProfileAttachment has already logged ERROR and raised alarm.
            // Re-log at SBB level so the container sees the issue in its own MDC context.
            LOG.error("[HelloWorld] sbbPassivate: checkpoint failed for key={} — recovery data may be stale",
                    key);
        }
    }

    @Override
    public void sbbRemove() { }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (!(event instanceof HttpWebRequestEvent req)) {
            return;
        }
        HttpReply reply;
        try {
            reply = dispatch(req);
        } catch (RuntimeException ex) {
            LOG.error("[gateway] handler failed for {} {}", req.getMethod(), req.getPath(), ex);
            reply = HttpReply.html(500, "Internal error");
        }
        RaCommandPort port = this.http;
        if (port == null) {
            LOG.warn("[gateway] no command port injected — dropping response for {}", req.getPath());
            return;
        }
        port.sendCommand(new HttpServerCommand.HttpResponseExCommand(
                req.getSessionId(), reply.status(), reply.contentType(),
                reply.text(), reply.binary(), reply.headers()));
    }

    private HttpReply dispatch(HttpWebRequestEvent req) {
        // Global per-endpoint counters → /api/telemetry/endpoints (+ Micrometer).
        if (endpointHits != null) {
            endpointHits.record(req.getMethod(), req.getPath());
        }
        if ("/health".equals(req.getPath())) {
            return HttpReply.json("{\"status\":\"ok\"}");
        }
        if (monitor != null) {
            return monitor.handle(req).orElseGet(() -> hello(req));
        }
        return hello(req);
    }

    private HttpReply hello(HttpWebRequestEvent req) {
        String userAgent = req.getUserAgent() != null ? req.getUserAgent() : "unknown";
        String profileKey = req.getSessionId() != null && !req.getSessionId().isBlank()
                ? req.getSessionId()
                : "anon";
        boundProfileKey = profileKey;

        int hits = 1;
        String checkpoint = "{\"hits\":1,\"lastPath\":\"" + escapeJson(req.getPath()) + "\"}";
        try {
            SessionProfile session = profiles.getOrCreateSession(profileKey);
            // Phase 3 restore: read last checkpoint from the profile row.
            // attachment.restoreCheckpoint returns the value written by the previous entity's
            // sbbPassivate() — this is the canonical Phase 3 recovery path (D4 in the plan).
            String restored = attachment.restoreCheckpoint(SessionProfile.TABLE_NAME, profileKey)
                    .orElse(null);
            // Also accept the raw checkpointJson field for backward compatibility.
            String prior = restored != null ? restored : session.getCheckpointJson();
            hits = parseHits(prior) + 1;
            checkpoint = "{\"hits\":" + hits
                    + ",\"lastPath\":\"" + escapeJson(req.getPath()) + "\""
                    + ",\"lastUa\":\"" + escapeJson(userAgent) + "\"}";
            // Write new checkpoint — C3 undo-log is active (we're inside event delivery).
            session.setCheckpointJson(checkpoint);
            session.setLastActivityId(req.getPath());
        } catch (Exception ex) {
            LOG.error("[HelloWorld] profile session update failed for key={}", profileKey, ex);
        }

        LOG.info("[HelloWorld] {} {} — hits={} key={} ua={}",
                req.getMethod(), req.getPath(), hits, profileKey, userAgent);
        return HttpReply.html(
                "Hello World from micro-jainslee (Quarkus)<br/>"
                        + "session hits=" + hits + " profileKey=" + escapeHtml(profileKey));
    }

    private String buildCheckpointJson(SessionProfile session) {
        String existing = session.getCheckpointJson();
        int hits = parseHits(existing);
        return "{\"hits\":" + hits
                + ",\"passivateTs\":" + System.currentTimeMillis()
                + ",\"lastActivity\":\"" + escapeJson(session.getLastActivityId()) + "\"}";
    }

    private static int parseHits(String checkpointJson) {
        if (checkpointJson == null || checkpointJson.isBlank()) {
            return 0;
        }
        int i = checkpointJson.indexOf("\"hits\":");
        if (i < 0) {
            return 0;
        }
        int start = i + "\"hits\":".length();
        int end = start;
        while (end < checkpointJson.length() && Character.isDigit(checkpointJson.charAt(end))) {
            end++;
        }
        if (end == start) {
            return 0;
        }
        try {
            return Integer.parseInt(checkpointJson.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
