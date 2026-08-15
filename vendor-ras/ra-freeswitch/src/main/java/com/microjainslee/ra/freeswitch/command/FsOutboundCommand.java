package com.microjainslee.ra.freeswitch.command;

import com.microjainslee.api.OutboundCommand;

/**
 * Sealed ESL outbound commands (SBB → {@code freeswitch-ra}).
 */
public sealed interface FsOutboundCommand extends OutboundCommand
        permits FsApi, FsBgapi, FsSubscribe {
}
