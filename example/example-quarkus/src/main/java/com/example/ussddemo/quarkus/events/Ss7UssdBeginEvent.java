/*
 * micro-jainslee 1.1.0 -- example application (example-quarkus)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.quarkus.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;

/** Internal MAP leg: HttpServerSbb routes to Ss7UssdIngressSbb after profile lookup. */
@EventType(name = "Ss7UssdBegin", vendor = "com.example.ussddemo.quarkus", version = "1.0")
public final class Ss7UssdBeginEvent implements SleeEvent {

    private final String sessionId;
    private final String msisdn;
    private final String ussdString;
    private final int menuTier;

    public Ss7UssdBeginEvent(String sessionId, String msisdn, String ussdString, int menuTier) {
        this.sessionId = sessionId;
        this.msisdn = msisdn;
        this.ussdString = ussdString;
        this.menuTier = menuTier;
    }

    public String getSessionId() { return sessionId; }
    public String getMsisdn() { return msisdn; }
    public String getUssdString() { return ussdString; }
    public int getMenuTier() { return menuTier; }
}
