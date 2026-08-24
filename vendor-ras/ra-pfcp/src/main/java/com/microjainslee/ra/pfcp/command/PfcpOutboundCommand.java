package com.microjainslee.ra.pfcp.command;

import com.microjainslee.api.OutboundCommand;

public sealed interface PfcpOutboundCommand extends OutboundCommand
        permits SendPfcpMessage, PfcpAssociateCommand, PfcpProgramSession, PfcpHeartbeatCommand,
        PfcpSessionEstablishment, PfcpSessionModification, PfcpSessionDeletion, PfcpSessionReport {
}
