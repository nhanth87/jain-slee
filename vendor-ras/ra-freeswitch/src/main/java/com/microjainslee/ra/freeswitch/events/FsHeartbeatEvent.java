package com.microjainslee.ra.freeswitch.events;

import java.util.Map;

/** ESL {@code HEARTBEAT} event (headers only — no full body dump on hot path). */
public record FsHeartbeatEvent(
        String eventName,
        Map<String, String> headers
) implements FsEvent {
    public FsHeartbeatEvent {
        eventName = eventName == null ? "HEARTBEAT" : eventName;
        headers = headers == null || headers.isEmpty() ? Map.of() : Map.copyOf(headers);
    }
}
