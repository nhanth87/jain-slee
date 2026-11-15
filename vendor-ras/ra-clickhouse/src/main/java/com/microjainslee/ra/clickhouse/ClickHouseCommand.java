/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.clickhouse;

import com.microjainslee.api.OutboundCommand;

/**
 * Commands accepted by {@link ClickHouseRaEndpoint} (port 2: SBB → RA).
 *
 * @param <R> app row type, carried to the writer untouched
 */
public sealed interface ClickHouseCommand<R> extends OutboundCommand
        permits ClickHouseCommand.Store {

    /** Persist one row. Never blocks the caller. */
    record Store<R>(R row) implements ClickHouseCommand<R> { }
}
