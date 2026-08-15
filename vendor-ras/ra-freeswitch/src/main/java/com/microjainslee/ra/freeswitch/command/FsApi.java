package com.microjainslee.ra.freeswitch.command;

/** Synchronous ESL {@code api} — response arrives as {@code FsApiResponseEvent}. */
public record FsApi(String requestId, String command) implements FsOutboundCommand {
    public FsApi {
        requestId = requestId == null || requestId.isBlank() ? "anon" : requestId;
        command = command == null ? "" : command.trim();
    }
}
