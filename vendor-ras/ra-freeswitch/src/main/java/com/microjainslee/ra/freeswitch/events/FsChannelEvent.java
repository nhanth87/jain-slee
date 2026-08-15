package com.microjainslee.ra.freeswitch.events;

import java.util.Map;

/** ESL channel lifecycle event ({@code CHANNEL_CREATE}, {@code CHANNEL_HANGUP}, …). */
public record FsChannelEvent(
        String eventName,
        String uuid,
        Map<String, String> headers
) implements FsEvent {
    public FsChannelEvent {
        eventName = eventName == null ? "" : eventName;
        uuid = uuid == null ? "" : uuid;
        headers = headers == null || headers.isEmpty() ? Map.of() : Map.copyOf(headers);
    }
}
