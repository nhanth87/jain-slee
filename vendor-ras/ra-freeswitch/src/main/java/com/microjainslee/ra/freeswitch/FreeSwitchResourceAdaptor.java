package com.microjainslee.ra.freeswitch;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.ra.freeswitch.command.FsApi;
import com.microjainslee.ra.freeswitch.command.FsBgapi;
import com.microjainslee.ra.freeswitch.command.FsOutboundCommand;
import com.microjainslee.ra.freeswitch.command.FsSubscribe;
import com.microjainslee.ra.freeswitch.esl.EslSession;
import com.microjainslee.ra.freeswitch.events.FsApiResponseEvent;
import com.microjainslee.ra.freeswitch.events.FsChannelEvent;
import com.microjainslee.ra.freeswitch.events.FsHeartbeatEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FreeSWITCH ESL Resource Adaptor — control plane only (not SIP, not RTP).
 */
public final class FreeSwitchResourceAdaptor {

    private static final Logger LOG = LogManager.getLogger(FreeSwitchResourceAdaptor.class);

    /** Grill C=2 — single link activity for HEARTBEAT / API responses. */
    public static final String LINK_ACTIVITY_ID = "fs-link";

    private FreeSwitchRaConfig config = new FreeSwitchRaConfig();
    private RaBootstrapPort bootstrapPort;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile boolean live;
    private volatile EslSession eventSession;
    private volatile ExecutorService eventLoop;
    private final Object apiLock = new Object();
    private volatile ActivityHandle linkActivity;
    private final ConcurrentHashMap<String, ActivityHandle> channelActivities = new ConcurrentHashMap<>();

    public void setConfig(FreeSwitchRaConfig config) {
        this.config = config != null ? config : new FreeSwitchRaConfig();
    }

    public void setBootstrapPort(RaBootstrapPort bootstrapPort) {
        this.bootstrapPort = bootstrapPort;
    }

    public boolean live() {
        return live;
    }

    public FreeSwitchRaConfig config() {
        return config;
    }

    public void raActive() {
        if (!active.compareAndSet(false, true)) {
            return;
        }
        ensureLinkActivity();
        if (config.autoSubscribe()) {
            startEventSocket(config.subscribeEvents());
        }
        LOG.info("[ra-freeswitch] ACTIVE host={}:{} autoSubscribe={} linkActivity={}",
                config.host(), config.port(), config.autoSubscribe(), LINK_ACTIVITY_ID);
    }

    public void raInactive() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        stopEventSocket();
        endAllChannelActivities();
        endLinkActivity();
        live = false;
        LOG.info("[ra-freeswitch] INACTIVE");
    }

    public void sendOutbound(FsOutboundCommand cmd) {
        if (cmd == null || !active.get()) {
            return;
        }
        switch (cmd) {
            case FsApi c -> runApi(c.requestId(), c.command(), false);
            case FsBgapi c -> runApi(c.requestId(), c.command(), true);
            case FsSubscribe c -> startEventSocket(c.events());
        }
    }

    /**
     * Sync ESL {@code api} for admin bridge (CDI). Soft-fail body when FS down.
     */
    public String apiSync(String command) {
        synchronized (apiLock) {
            try (EslSession session = newSession()) {
                session.connect();
                live = true;
                String body = session.api(command);
                return body == null ? "" : body;
            } catch (IOException ex) {
                live = false;
                LOG.debug("[ra-freeswitch] apiSync failed: {}", ex.getMessage());
                return "-ERR ESL unavailable: " + ex.getMessage();
            }
        }
    }

    public String bgapiSync(String command) {
        synchronized (apiLock) {
            try (EslSession session = newSession()) {
                session.connect();
                live = true;
                String body = session.bgapi(command);
                return body == null ? "" : body;
            } catch (IOException ex) {
                live = false;
                LOG.debug("[ra-freeswitch] bgapiSync failed: {}", ex.getMessage());
                return "-ERR ESL unavailable: " + ex.getMessage();
            }
        }
    }

    private void runApi(String requestId, String command, boolean bgapi) {
        String body;
        boolean ok;
        synchronized (apiLock) {
            try (EslSession session = newSession()) {
                session.connect();
                live = true;
                body = bgapi ? session.bgapi(command) : session.api(command);
                ok = body != null && !body.startsWith("-ERR");
            } catch (IOException ex) {
                live = false;
                body = "-ERR ESL unavailable: " + ex.getMessage();
                ok = false;
                LOG.debug("[ra-freeswitch] {} failed requestId={}: {}",
                        bgapi ? "bgapi" : "api", requestId, ex.getMessage());
            }
        }
        fire(new FsApiResponseEvent(requestId, command, body, ok, live), linkHandle());
    }

    private void startEventSocket(String events) {
        stopEventSocket();
        if (events == null || events.isBlank()) {
            return;
        }
        eventLoop = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ra-freeswitch-esl-events");
            t.setDaemon(true);
            return t;
        });
        final String ev = events;
        eventLoop.execute(() -> eventReadLoop(ev));
    }

    private void stopEventSocket() {
        EslSession s = eventSession;
        eventSession = null;
        if (s != null) {
            s.close();
        }
        ExecutorService ex = eventLoop;
        eventLoop = null;
        if (ex != null) {
            ex.shutdownNow();
        }
    }

    private void eventReadLoop(String events) {
        while (active.get()) {
            try (EslSession session = newEventSession()) {
                eventSession = session;
                session.connect();
                session.subscribe(events);
                live = true;
                LOG.info("[ra-freeswitch] event socket subscribed: {}", events);
                while (active.get()) {
                    try {
                        Map<String, String> msg = session.readNextMessage();
                        if (msg == null || msg.isEmpty()) {
                            break;
                        }
                        dispatchInbound(msg);
                    } catch (java.net.SocketTimeoutException timeout) {
                        // HEARTBEAT is ~20s; SO_TIMEOUT must not flip eslLive.
                        live = true;
                    }
                }
            } catch (IOException ex) {
                boolean wasLive = live;
                live = false;
                if (wasLive) {
                    LOG.warn("[ra-freeswitch] event socket lost: {}", ex.getMessage());
                } else {
                    LOG.debug("[ra-freeswitch] event socket: {}", ex.getMessage());
                }
                if (!active.get()) {
                    break;
                }
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } finally {
                eventSession = null;
            }
        }
    }

    private void dispatchInbound(Map<String, String> headers) {
        String name = headers.getOrDefault("Event-Name", "");
        if (name.isBlank() && headers.containsKey("_body")) {
            // nested event body often has Event-Name= lines
            String body = headers.get("_body");
            for (String line : body.split("\n")) {
                if (line.startsWith("Event-Name:")) {
                    name = line.substring("Event-Name:".length()).trim();
                    break;
                }
            }
            headers = parseBodyHeaders(body, headers);
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if ("HEARTBEAT".equals(upper)) {
            // C=2 — link activity
            fire(new FsHeartbeatEvent(name, slim(headers)), linkHandle());
            return;
        }
        if (upper.startsWith("CHANNEL_")) {
            String uuid = headers.getOrDefault("Unique-ID",
                    headers.getOrDefault("Channel-Call-UUID", ""));
            // C=3 — per-channel UUID activity
            ActivityHandle ch = channelHandle(uuid);
            fire(new FsChannelEvent(name, uuid, slim(headers)), ch);
            if (isChannelTerminal(upper)) {
                endChannelActivity(uuid);
            }
        }
    }

    static boolean isChannelTerminal(String eventNameUpper) {
        return "CHANNEL_HANGUP".equals(eventNameUpper)
                || "CHANNEL_HANGUP_COMPLETE".equals(eventNameUpper)
                || "CHANNEL_DESTROY".equals(eventNameUpper);
    }

    /** Grill C=3 — activity id for a FreeSWITCH channel UUID. */
    public static String channelActivityId(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return "fs-ch-unknown";
        }
        return "fs-ch-" + uuid;
    }

    private static Map<String, String> parseBodyHeaders(String body, Map<String, String> base) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>(base);
        if (body == null) {
            return out;
        }
        for (String line : body.split("\n")) {
            int c = line.indexOf(':');
            if (c > 0) {
                out.put(line.substring(0, c).trim(), line.substring(c + 1).trim());
            }
        }
        return out;
    }

    /** Drop bulky keys before firing (hot-path). */
    private static Map<String, String> slim(Map<String, String> headers) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        for (var e : headers.entrySet()) {
            if ("_raw".equals(e.getKey()) || "_body".equals(e.getKey())) {
                continue;
            }
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private void fire(com.microjainslee.api.SleeEvent event, ActivityHandle handle) {
        RaBootstrapPort bp = bootstrapPort;
        if (bp == null || handle == null) {
            return;
        }
        bp.fireEvent(event, handle, null);
    }

    private ActivityHandle linkHandle() {
        ensureLinkActivity();
        return linkActivity;
    }

    private void ensureLinkActivity() {
        RaBootstrapPort bp = bootstrapPort;
        if (bp == null) {
            return;
        }
        if (linkActivity == null) {
            synchronized (this) {
                if (linkActivity == null) {
                    linkActivity = bp.createActivityHandle(LINK_ACTIVITY_ID);
                }
            }
        }
    }

    private ActivityHandle channelHandle(String uuid) {
        RaBootstrapPort bp = bootstrapPort;
        if (bp == null) {
            return null;
        }
        String id = channelActivityId(uuid);
        return channelActivities.computeIfAbsent(id, bp::createActivityHandle);
    }

    private void endChannelActivity(String uuid) {
        RaBootstrapPort bp = bootstrapPort;
        String id = channelActivityId(uuid);
        ActivityHandle handle = channelActivities.remove(id);
        if (bp != null && handle != null) {
            bp.endActivity(handle);
            LOG.debug("[ra-freeswitch] ended channel activity {}", id);
        }
    }

    private void endAllChannelActivities() {
        RaBootstrapPort bp = bootstrapPort;
        for (var e : channelActivities.entrySet()) {
            if (bp != null) {
                bp.endActivity(e.getValue());
            }
        }
        channelActivities.clear();
    }

    private void endLinkActivity() {
        RaBootstrapPort bp = bootstrapPort;
        ActivityHandle handle = linkActivity;
        linkActivity = null;
        if (bp != null && handle != null) {
            bp.endActivity(handle);
        }
    }

    private EslSession newSession() {
        return new EslSession(config.host(), config.port(), config.password(),
                config.connectTimeoutMs(), config.readTimeoutMs());
    }

    /** Event socket waits past FS HEARTBEAT (~20s); apiSync keeps the short timeout. */
    private EslSession newEventSession() {
        int readMs = Math.max(config.readTimeoutMs(), 30_000);
        return new EslSession(config.host(), config.port(), config.password(),
                config.connectTimeoutMs(), readMs);
    }

    /**
     * Hot-swap ESL target (paired failover). Restarts event socket with new host/port.
     */
    public void reconfigure(FreeSwitchRaConfig next) {
        if (next == null) {
            return;
        }
        synchronized (apiLock) {
            this.config = next;
            if (active.get() && config.autoSubscribe()) {
                startEventSocket(config.subscribeEvents());
            }
            LOG.info("[ra-freeswitch] reconfigured ESL {}:{}", config.host(), config.port());
        }
    }
}
