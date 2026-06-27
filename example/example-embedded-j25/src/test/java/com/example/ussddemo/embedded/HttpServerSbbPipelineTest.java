package com.example.ussddemo.embedded;

import com.example.ussddemo.events.HttpUssdBeginEvent;
import com.example.ussddemo.sbbs.HttpServerSbb;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Isolated pipeline test without the background {@link EmbeddedUssdMain} thread.
 */
public class HttpServerSbbPipelineTest {

    private static TestGrpcMenuServer grpcServer;
    private static MicroSleeContainer container;
    private static EmbeddedUssdBootstrap bootstrap;
    private static UssdSessionStore sessionStore;

    @BeforeClass
    public static void boot() throws Exception {
        grpcServer = TestGrpcMenuServer.startOnFreePort();
        sessionStore = new UssdSessionStore();
        UssdCallbackDispatcher dispatcher = new UssdCallbackDispatcher();
        UssdDemoRuntime runtime = new UssdDemoRuntime(sessionStore, dispatcher);

        container = new MicroSleeContainer(MicroSleeConfiguration.builder()
                .preferVirtualThreads(true)
                .build());
        bootstrap = new EmbeddedUssdBootstrap(container, sessionStore);
        bootstrap.registerSbbTypesOnly();
        container.start();
        bootstrap.seedProfilesOnly();
        bootstrap.wireGrpcRa("127.0.0.1", grpcServer.port());

        EmbeddedUssdMain.bindForTest(container, bootstrap, runtime, dispatcher);
    }

    @AfterClass
    public static void shutdown() {
        if (bootstrap != null) {
            bootstrap.shutdown();
        }
        if (container != null) {
            container.stop();
        }
        if (grpcServer != null) {
            grpcServer.close();
        }
        EmbeddedUssdMain.clearTestBinding();
    }

    @Test
    public void fullPipelineCompletes() throws Exception {
        String sessionId = "pipeline-1";
        var aci = container.createActivityContext(sessionId);
        bootstrap.prepareHttpSession(sessionId, null, aci);
        Thread.sleep(300L);
        container.routeEvent(new HttpUssdBeginEvent(
                sessionId, "251911000001", "*123#", null), aci);
        Thread.sleep(5000L);
        UssdSessionStore.SessionRecord rec = sessionStore.get(sessionId);
        assertNotNull(rec);
        assertEquals(UssdSessionStore.Status.COMPLETED, rec.getStatus());
        assertNotNull(rec.getResponseText());
    }
}
