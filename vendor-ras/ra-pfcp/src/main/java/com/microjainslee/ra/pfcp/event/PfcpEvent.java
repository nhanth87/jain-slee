package com.microjainslee.ra.pfcp.event;

import com.microjainslee.api.SleeEvent;

public sealed interface PfcpEvent extends SleeEvent
        permits PfcpMessageEvent, PfcpAssociationEvent, PfcpHeartbeatEvent {
}
