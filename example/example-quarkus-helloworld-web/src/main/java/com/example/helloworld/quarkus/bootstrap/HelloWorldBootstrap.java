package com.example.helloworld.quarkus.bootstrap;

import com.example.helloworld.quarkus.http.MonitorHandler;
import com.example.helloworld.quarkus.profile.HelloWorldProfileManager;
import com.example.helloworld.quarkus.sbbs.HelloWorldSbb;
import com.example.helloworld.quarkus.telemetry.AppTelemetry;
import com.example.helloworld.quarkus.telemetry.EndpointHitStore;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.ProfileAttachment;
import com.microjainslee.ra.httpserver.HttpServerRaEndpoint;
import com.microjainslee.ra.httpserver.HttpServerResourceAdaptor;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;
import com.microjainslee.telemetry.TelemetryPort;

import io.quarkus.runtime.StartupEvent;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Bootstrap — Quarkus wiring for the HelloWorld template. Optional telemetry
 * sits on top of the core container; the dashboard and telemetry APIs ride the
 * same {@code ra-http-server} as the app response through {@link HelloWorldSbb}.
 *
 * <p>Observes {@link StartupEvent} for eager init. On every Quarkus live-reload
 * restart this re-wires the HTTP RA and SBB factory so {@code HelloWorldSbb}
 * changes are not stuck in a previous classloader's pool.</p>
 */
@ApplicationScoped
public final class HelloWorldBootstrap {

    private static final Logger LOG = LogManager.getLogger(HelloWorldBootstrap.class);

    @Inject
    MicroSleeContainer container;

    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "http.ra.port", defaultValue = "8080")
    int httpRaPort;

    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "microjainslee.telemetry.enabled", defaultValue = "true")
    boolean telemetryEnabled;

    private volatile HttpServerRaEndpoint httpEndpoint;
    private volatile AppTelemetry appTelemetry;

    void onStart(@Observes StartupEvent ev) {
        LOG.info("HelloWorld bootstrap triggered by StartupEvent (rewire for live-reload)");
        rewire();
    }

    /**
     * Tear down app-owned RA/telemetry, drop stale SBB pools, then register again.
     * Safe to call on each {@code quarkus:dev} restart.
     */
    private void rewire() {
        teardownAppWiring();

        // Previous ClassLoader's HelloWorldSbb pool would otherwise keep serving
        // old bytecode after live-reload (registry keys by Class identity).
        int dropped = container.getSbbTypeRegistry()
                .unregisterByName(HelloWorldSbb.class.getSimpleName());
        if (dropped > 0) {
            LOG.info("Dropped {} stale SBB pool(s) for HelloWorldSbb (live-reload)", dropped);
        }

        if (container.getState() != MicroSleeContainer.State.STARTED) {
            container.start();
        }

        TelemetryPort telemetry = null;
        if (telemetryEnabled) {
            appTelemetry = new AppTelemetry();
            telemetry = appTelemetry.install(container);
        } else {
            LOG.info("telemetry module disabled (microjainslee.telemetry.enabled=false)");
        }

        // Always count HTTP endpoints; bind Micrometer when telemetry is on.
        EndpointHitStore endpointHits = new EndpointHitStore();
        endpointHits.bindTelemetry(telemetry);
        MonitorHandler monitor = new MonitorHandler(telemetry, endpointHits);

        HelloWorldProfileManager profiles = new HelloWorldProfileManager(container.getProfileFacility());
        profiles.provisionTables();
        LOG.info("HelloWorld profile tables provisioned (SubscriberSession, AppUser) "
                + "— hot store = ProfileFacility; durable = Infinispan when installDurableStore is wired");

        // Phase 3 — ProfileAttachment: encapsulates checkpoint / restore with C9 alarm contract.
        ProfileAttachment attachment = new ProfileAttachment(
                container.getProfileFacility(), container.getAlarmFacility());

        container.registerSbbType(HelloWorldSbb.class,
                () -> new HelloWorldSbb(monitor, profiles, endpointHits, attachment));
        container.createIesDispatcher();
        container.mapEventToSbb(HttpWebRequestEvent.class, "HelloWorldSbb");
        wireHttpRa();

        LOG.info("HelloWorld bootstrap complete. App + dashboard on http://localhost:{} "
                + "(app: /, endpoints: /api/telemetry/endpoints, dashboard: /telemetry)", httpRaPort);
    }

    @PreDestroy
    void shutdown() {
        // Tear down app RA/telemetry only — MicroSleeContainer is owned by
        // adapter-quarkus (shutdown hook). Stopping it here would break live-reload.
        teardownAppWiring();
    }

    private void teardownAppWiring() {
        HttpServerRaEndpoint ep = httpEndpoint;
        httpEndpoint = null;
        if (ep != null) {
            try {
                ep.deactivate();
            } catch (RuntimeException re) {
                LOG.warn("HTTP RA deactivate during rewire: {}", re.getMessage());
            }
        }
        AppTelemetry tel = appTelemetry;
        appTelemetry = null;
        if (tel != null) {
            try {
                tel.close();
            } catch (RuntimeException re) {
                LOG.warn("telemetry close during rewire: {}", re.getMessage());
            }
        }
    }

    private void wireHttpRa() {
        HttpServerResourceAdaptor ra = new HttpServerResourceAdaptor();
        ra.setPort(httpRaPort);
        ra.setHost("0.0.0.0");

        httpEndpoint = new HttpServerRaEndpoint(ra);
        httpEndpoint.setPort(httpRaPort);

        container.registerRa(httpEndpoint, httpEndpoint);
        LOG.info("ra-http-server registered on port {}", httpRaPort);
    }
}
