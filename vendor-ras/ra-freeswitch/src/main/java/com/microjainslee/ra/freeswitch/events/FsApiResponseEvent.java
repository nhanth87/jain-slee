package com.microjainslee.ra.freeswitch.events;

/** Result of {@code FsApi} / {@code FsBgapi}. */
public record FsApiResponseEvent(
        String requestId,
        String command,
        String body,
        boolean ok,
        boolean live
) implements FsEvent {
}
