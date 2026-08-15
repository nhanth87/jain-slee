package com.microjainslee.ra.freeswitch;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import com.microjainslee.ra.freeswitch.command.FsOutboundCommand;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 3-port adapter for {@link FreeSwitchResourceAdaptor}. RA name: {@code freeswitch-ra}.
 */
public final class FreeSwitchRaEndpoint implements RaEndpointPort, RaCommandPort {

    private static final Logger LOG = LogManager.getLogger(FreeSwitchRaEndpoint.class);

    private final FreeSwitchResourceAdaptor delegate;

    public FreeSwitchRaEndpoint(FreeSwitchResourceAdaptor delegate) {
        this.delegate = delegate;
    }

    public FreeSwitchRaEndpoint() {
        this(new FreeSwitchResourceAdaptor());
    }

    public void setConfig(FreeSwitchRaConfig config) {
        delegate.setConfig(config);
    }

    public FreeSwitchResourceAdaptor delegate() {
        return delegate;
    }

    @Override
    public String getRaName() {
        return "freeswitch-ra";
    }

    @Override
    public void activate(RaBootstrapPort bootstrap) {
        delegate.setBootstrapPort(bootstrap);
        delegate.raActive();
        LOG.info("FreeSWITCH RA endpoint activated");
    }

    @Override
    public void deactivate() {
        try {
            delegate.raInactive();
        } catch (RuntimeException e) {
            LOG.warn("raInactive failed", e);
        }
        LOG.info("FreeSWITCH RA endpoint deactivated");
    }

    @Override
    public void sendCommand(OutboundCommand command) {
        if (command instanceof FsOutboundCommand fs) {
            delegate.sendOutbound(fs);
        } else {
            LOG.warn("freeswitch-ra unknown command: {}",
                    command == null ? "null" : command.getClass().getName());
        }
    }
}
