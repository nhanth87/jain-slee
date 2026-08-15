package com.microjainslee.ra.freeswitch.events;

import com.microjainslee.api.SleeEvent;

/** Marker for FreeSWITCH ESL inbound/outbound-completion events. */
public sealed interface FsEvent extends SleeEvent
        permits FsApiResponseEvent, FsHeartbeatEvent, FsChannelEvent {
}
