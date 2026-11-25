package com.microjainslee.ra.diameter.events;

/**
 * No answer to an outbound request within the RA's Tx timer ({@code requestTimeoutMs}):
 * fired on the request's Session-Id activity so the SBB that sent it finishes its procedure
 * (e.g. a PCEF falls back to its local policy, RFC 4006 §5.5 / TS 29.212 §4.5.1) instead of
 * blocking a thread on the answer.
 */
public record DiameterTimeoutEvent(String sessionId, long applicationId, int commandCode)
        implements DiameterEvent {
}
