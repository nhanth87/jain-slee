package com.example.helloworld.min.quarkus.bootstrap;

import com.example.helloworld.min.quarkus.sbbs.HelloWorldSbb;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.httpserver.HttpServerRaEndpoint;
import com.microjainslee.ra.httpserver.HttpServerResourceAdaptor;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Bootstrap — wires ra-http-server + HelloWorldSbb into MicroSleeContainer.
 *
 * <p>SLEE-compliant architecture: ALL HTTP traffic flows through
 * ra-http-server (port 8081). No direct Vert.x, no separate REST endpoints.
 * The Quarkus HTTP server (port 8080) is NOT used for SLEE traffic.</p>
 *
 * <p>Flow: HTTP request → ra-http-server → HttpWebRequestEvent
 * → EventRouter → HelloWorldSbb.onEvent() → HttpResponseCommand
 * → ra-http-server → HTTP response.</p>
 */
@ApplicationScoped
public final class HelloWorldBootstrap {

    private static final Logger LOG = LogManager.getLogger(HelloWorldBootstrap.class);

    @Inject
    MicroSleeContainer container;

    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "http.ra.port", defaultValue = "8081")
    int httpRaPort;

    private volatile HttpServerRaEndpoint httpEndpoint;

    @PostConstruct
    void init() {
        if (container.getState() != MicroSleeContainer.State.STARTED) {
            container.start();
        }

        // Register SBB
        container.registerSbbType(HelloWorldSbb.class,
                () -> new HelloWorldSbb(container));
        container.createIesDispatcher();
        container.mapEventToSbb(HttpWebRequestEvent.class, "HelloWorldSbb");

        // Wire ra-http-server
        wireHttpRa();

        LOG.info("HelloWorld bootstrap complete. ra-http-server listening on :{}", httpRaPort);
    }

    @PreDestroy
    void shutdown() {
        if (httpEndpoint != null) {
            httpEndpoint.deactivate();
        }
        if (container.getState() == MicroSleeContainer.State.STARTED) {
            container.stop();
        }
    }

    private void wireHttpRa() {
        HttpServerResourceAdaptor ra = new HttpServerResourceAdaptor();
        ra.setPort(httpRaPort);

        httpEndpoint = new HttpServerRaEndpoint(ra);
        httpEndpoint.setPort(httpRaPort);

        container.registerRa(httpEndpoint, httpEndpoint);
        LOG.info("ra-http-server registered on port {}", httpRaPort);
    }
}
