package com.microjainslee.ra.sipservlet.events;

/** SIP REGISTER event — UA registration request. */
public record SipRegisterEvent(
    String callId,
    String fromUri,
    String toUri,
    String contactUri,
    int expires,
    String authorization,
    String pathHeader
) implements SipEvent {
    public SipRegisterEvent(String callId, String fromUri, String toUri, String contactUri, int expires) {
        this(callId, fromUri, toUri, contactUri, expires, null, null);
    }

    @Override public String method() { return "REGISTER"; }
}
