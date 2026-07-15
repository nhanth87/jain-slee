package com.example.helloworld.min.quarkus.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.httpserver.command.HttpServerCommand;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Minimal SBB that handles HTTP web requests from ra-http-server.
 * Returns a "Hello World" message through the ra-http-server command port.
 *
 * <p>This is the SLEE-compliant pattern: the SBB receives events only
 * through the SLEE pipeline, never directly from HTTP.</p>
 */
public final class HelloWorldSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(HelloWorldSbb.class);

    private final MicroSleeContainer container;

    /** Injected by container at activation time. Must match HttpServerRaEndpoint.getRaName(). */
    @InjectRa(name = "http-server-ra")
    private volatile RaCommandPort httpCommandPort;

    public HelloWorldSbb(MicroSleeContainer container) {
        this.container = container;
    }

    @Override
    public void sbbCreate() {
        LOG.debug("HelloWorldSbb created");
    }

    @Override
    public void sbbActivate() {
        LOG.debug("HelloWorldSbb activated");
    }

    @Override
    public void sbbPassivate() { }

    @Override
    public void sbbRemove() { }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (event instanceof HttpWebRequestEvent req) {
            onWebRequest(req);
        }
    }

    private void onWebRequest(HttpWebRequestEvent event) {
        String userAgent = event.getUserAgent() != null ? event.getUserAgent() : "unknown";
        LOG.info("[HelloWorld] {} {} userAgent={}", event.getMethod(), event.getPath(), userAgent);

        String responseBody = "{\"message\":\"Hello World\",\"userAgent\":\""
                + userAgent + "\"}";

        // Reply through ra-http-server command port
        httpCommandPort.sendCommand(new HttpServerCommand.HttpResponseCommand(
                event.getSessionId(), 200, "application/json", responseBody));
    }
}
