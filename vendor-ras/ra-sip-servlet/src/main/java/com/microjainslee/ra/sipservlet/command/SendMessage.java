/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.sipservlet.command;

/**
 * Out-of-dialog SIP MESSAGE (RFC 3428) — used for USSI / IMS USSD NI push.
 *
 * <p>{@code callId} identifies the outbound transaction; {@code toUri} is the
 * request-URI / To; {@code fromUri} is the From address; body is optional.</p>
 */
public record SendMessage(
        String callId,
        String fromUri,
        String toUri,
        String contentType,
        String body
) implements SipOutboundCommand {}
