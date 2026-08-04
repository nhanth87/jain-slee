/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.ispn.ra;

/**
 * How {@link com.microjainslee.ms.ispn.IspnQueueServer} delivers inbox entries.
 *
 * <ul>
 *   <li>{@link #HANDLER} — invoke {@code SleeServiceHandler} (default; demos).</li>
 *   <li>{@link #EVENT} — {@code RaBootstrapPort.fireEvent(MsRemoteRequestEvent)}.</li>
 * </ul>
 */
public enum InboundMode {
    HANDLER,
    EVENT
}
