/*
 * micro-jainslee 1.1.0 -- example application (example-quarkus)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.quarkus.rest;

/** REST view of a USSD session. */
public final class UssdSessionView {

    public String sessionId;
    public String status;
    public String responseText;
    public String errorMessage;

    public static UssdSessionView processing(String sessionId) {
        UssdSessionView view = new UssdSessionView();
        view.sessionId = sessionId;
        view.status = "PROCESSING";
        return view;
    }
}
