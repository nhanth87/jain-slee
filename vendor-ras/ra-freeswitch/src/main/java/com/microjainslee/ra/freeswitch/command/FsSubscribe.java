package com.microjainslee.ra.freeswitch.command;

/** (Re)subscribe ESL plain events, space-separated (e.g. {@code HEARTBEAT CHANNEL_CREATE}). */
public record FsSubscribe(String events) implements FsOutboundCommand {
    public FsSubscribe {
        events = events == null ? "" : events.trim();
    }
}
