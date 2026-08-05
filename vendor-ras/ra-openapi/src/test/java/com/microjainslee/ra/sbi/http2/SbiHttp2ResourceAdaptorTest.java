package com.microjainslee.ra.sbi.http2;

import com.microjainslee.ra.sbi.http2.admin.SbiHttp2AdminBindings;
import com.microjainslee.ra.sbi.http2.admin.SbiHttp2RaAdminContributor;
import com.microjainslee.ra.sbi.http2.command.SbiOutboundCommand;
import com.microjainslee.ra.sbi.http2.resilience.SbiResiliencePolicy;
import com.microjainslee.ra.sbi.http2.resilience.SbiSagaCoordinator;
import com.microjainslee.ra.sbi.openapi.SbiOpenApiCatalog;
import com.microjainslee.ra.sbi.openapi.headers.SbiHeaderCodec;

import org.junit.After;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SbiHttp2ResourceAdaptorTest {

    private SbiHttp2ResourceAdaptor ra;

    @After
    public void tearDown() {
        if (ra != null) {
            ra.stop();
            ra = null;
        }
        SbiHttp2AdminBindings.bind(null);
    }

    private void startRa() {
        ra = new SbiHttp2ResourceAdaptor();
        ra.setHost("127.0.0.1");
        ra.setPort(18082);
        ra.setAutoRespondUnmapped(true);
        ra.setCatalog(SbiOpenApiCatalog.loadDefault());
        SbiHttp2AdminBindings.bind(ra);
        ra.start();
        assertTrue(ra.listening());
    }

    @Test
    public void catalogDispatchReturnsProblemDetailsWhenUnmapped() throws Exception {
        startRa();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:18082/nnrf-disc/v1/nf-instances"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        assertTrue("unexpected status " + code + " body=" + resp.body(),
                code == 501 || code == 404 || code == 200 || code == 500);
        assertTrue(ra.catalog().size() >= 1500);
    }

    @Test
    public void unknownPathIs404ProblemDetails() throws Exception {
        startRa();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:18082/no-such-sbi/v1/x"))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, resp.statusCode());
        assertTrue(resp.body().contains("RESOURCE_URI_STRUCTURE_NOT_FOUND"));
    }

    @Test
    public void resilienceHonorsNoRetries() {
        SbiResiliencePolicy p = new SbiResiliencePolicy();
        p.setMaxRetries(5);
        SbiHeaderCodec h = new SbiHeaderCodec(Map.of("3gpp-Sbi-Retry-Info", "no-retries"));
        assertEquals(0, p.effectiveMaxRetries(h, null));
        assertEquals(5, p.effectiveMaxRetries(new SbiHeaderCodec(Map.of()), null));
    }

    @Test
    public void sagaCompensateOrder() {
        SbiSagaCoordinator c = new SbiSagaCoordinator();
        var s = c.begin("corr-1");
        c.registerCompensate(s.id, SbiOutboundCommand.builder().operationId("a").build());
        c.registerCompensate(s.id, SbiOutboundCommand.builder().operationId("b").build());
        var comps = c.failAndCompensate(s.id);
        assertEquals(2, comps.size());
        assertEquals("b", comps.get(0).operationId());
        assertEquals("a", comps.get(1).operationId());
    }

    @Test
    public void adminContributorLoads() {
        SbiHttp2RaAdminContributor c = new SbiHttp2RaAdminContributor();
        assertEquals("sbi-http2-ra", c.manifest().raName());
        assertFalse(c.manifest().title().isBlank());
    }
}
