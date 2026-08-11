package com.microjainslee.ra.sipservlet.command;

/** Send a BYE request to terminate a dialog. */
public record SendBye(String callId, String nextHopUri) implements SipOutboundCommand {

    public SendBye(String callId) {
        this(callId, null);
    }
}
