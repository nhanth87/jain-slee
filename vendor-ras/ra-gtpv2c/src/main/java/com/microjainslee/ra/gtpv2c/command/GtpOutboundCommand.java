package com.microjainslee.ra.gtpv2c.command;

import com.microjainslee.api.OutboundCommand;

public sealed interface GtpOutboundCommand extends OutboundCommand
        permits SendGtpv2Message, GtpEchoCommand {
}
