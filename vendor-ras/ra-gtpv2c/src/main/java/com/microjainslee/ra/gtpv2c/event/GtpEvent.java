package com.microjainslee.ra.gtpv2c.event;

import com.microjainslee.api.SleeEvent;

public sealed interface GtpEvent extends SleeEvent
        permits Gtpv2MessageEvent, GtpEchoEvent {
}
