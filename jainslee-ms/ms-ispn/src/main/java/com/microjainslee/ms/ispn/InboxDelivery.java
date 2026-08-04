/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.ispn;

/**
 * Delivers one inbox entry to business logic (handler or SLEE event path).
 */
@FunctionalInterface
public interface InboxDelivery {

    /**
     * @param entryKey  cache key in the inbox
     * @param entry     queue envelope
     * @param reply     write sync response (ignored when {@code entry.fireAndForget()})
     */
    void deliver(String entryKey, SleeQueueEntry entry, ReplyWriter reply) throws Exception;
}
