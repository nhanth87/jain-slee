package com.microjainslee.ra.httpclient;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.httpclient.command.HttpCallbackCommand;
import com.microjainslee.ra.httpclient.events.HttpCallbackCompletedEvent;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * PostRequest carries request headers (Cookie, Accept, correlation ids) to the server and the
 * completion event carries the response headers (Set-Cookie) back — the plumbing an SBB needs
 * to keep a servlet AS's HttpSession (JSESSIONID) across USSD menu turns. The RA itself keeps
 * no cookie state.
 */
public class HttpPostHeadersTest {

    private HttpServer server;
    private HttpCallbackClientRa ra;
    private final BlockingQueue<HttpCallbackCompletedEvent> events = new ArrayBlockingQueue<>(4);
    private final AtomicReference<Map<String, List<String>>> seenHeaders = new AtomicReference<>();

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/as", ex -> {
            seenHeaders.set(Map.copyOf(ex.getRequestHeaders()));
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("Set-Cookie", "JSESSIONID=ABC123; Path=/; HttpOnly");
            ex.getResponseHeaders().add("Set-Cookie", "route=n1; Path=/");
            ex.getResponseHeaders().add("Content-Type", "text/xml;charset=UTF-8");
            byte[] body = "<dialog/>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        ra = new HttpCallbackClientRa();
        ra.setMaxRetries(0);
        ra.setBootstrapPort(new RaBootstrapPort() {
            @Override
            public ActivityHandle createActivityHandle(String id) {
                return null;
            }

            @Override
            public void fireEvent(SleeEvent event, ActivityHandle handle, Address address) {
                events.add((HttpCallbackCompletedEvent) event);
            }
        });
        ra.raConfigure();
        ra.raActive();
    }

    @After
    public void tearDown() {
        ra.raStopping();
        ra.raUnconfigure();
        server.stop(0);
    }

    @Test
    public void requestHeadersGoOutAndResponseHeadersComeBack() throws Exception {
        HttpCallbackRaEndpoint ep = new HttpCallbackRaEndpoint(ra);
        ep.sendCommand(new HttpCallbackCommand.PostRequest("corr-1",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/as", "<dialog/>",
                "text/xml; charset=UTF-8",
                Map.of("Cookie", "JSESSIONID=OLD", "Accept", "text/xml",
                        "X-Ussd-Correlation-Id", "corr-1")));
        HttpCallbackCompletedEvent done = events.poll(10, TimeUnit.SECONDS);
        assertNotNull("no completion event", done);
        assertEquals(200, done.getStatusCode());
        assertEquals(List.of("JSESSIONID=ABC123; Path=/; HttpOnly", "route=n1; Path=/"),
                done.getHeaderValues("Set-Cookie"));
        Map<String, List<String>> h = seenHeaders.get();
        assertEquals(List.of("JSESSIONID=OLD"), h.get("Cookie"));
        assertEquals(List.of("text/xml"), h.get("Accept"));
        assertEquals(List.of("corr-1"), h.get("X-ussd-correlation-id"));
        assertTrue(h.get("Content-type").getFirst().startsWith("text/xml"));
    }

    @Test
    public void legacyJsonPostStillWorksAndReportsHeaders() throws Exception {
        ra.sendJsonPost("corr-2", "http://127.0.0.1:" + server.getAddress().getPort() + "/as",
                "{}", "application/json");
        HttpCallbackCompletedEvent done = events.poll(10, TimeUnit.SECONDS);
        assertNotNull(done);
        assertEquals(2, done.getHeaderValues("set-cookie").size());
    }

    @Test
    public void endSessionForgetsTheSession() throws Exception {
        HttpCallbackRaEndpoint ep = new HttpCallbackRaEndpoint(ra);
        ep.sendCommand(new HttpCallbackCommand.PostRequest("corr-9",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/as", "<dialog/>", "text/xml", Map.of()));
        assertNotNull(events.poll(10, TimeUnit.SECONDS));
        assertTrue(ra.haSupport().lookupOwner("corr-9").isPresent());
        ep.sendCommand(new HttpCallbackCommand.EndSession("corr-9"));
        assertTrue(ra.haSupport().lookupOwner("corr-9").isEmpty());
    }
}
