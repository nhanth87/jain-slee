package com.microjainslee.ra.freeswitch.command;

/** ESL {@code bgapi} — Job-UUID style; body still returned via {@code FsApiResponseEvent}. */
public record FsBgapi(String requestId, String command) implements FsOutboundCommand {
    public FsBgapi {
        requestId = requestId == null || requestId.isBlank() ? "anon" : requestId;
        command = command == null ? "" : command.trim();
    }
}
