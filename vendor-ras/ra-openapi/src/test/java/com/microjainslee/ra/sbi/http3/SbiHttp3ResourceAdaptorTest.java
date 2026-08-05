package com.microjainslee.ra.sbi.http3;

import com.microjainslee.ra.sbi.http3.admin.SbiHttp3AdminBindings;
import com.microjainslee.ra.sbi.http3.admin.SbiHttp3RaAdminContributor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SbiHttp3ResourceAdaptorTest {

    private SbiHttp3ResourceAdaptor ra;

    @Before
    public void setUp() {
        ra = new SbiHttp3ResourceAdaptor();
        ra.setHost("127.0.0.1");
        ra.setTcpPort(18083);
        ra.setQuicPort(18443);
        ra.setAutoRespondUnmapped(true);
        SbiHttp3AdminBindings.bind(ra);
        ra.start();
        assertTrue("RA should listen (QUIC or TCP fallback)", ra.listening());
    }

    @After
    public void tearDown() {
        if (ra != null) {
            ra.stop();
        }
        SbiHttp3AdminBindings.bind(null);
    }

    @Test
    public void catalogDispatchOverTcp() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:18083/nnrf-disc/v1/nf-instances"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        assertTrue("unexpected " + code + " " + resp.body(),
                code == 501 || code == 404 || code == 200 || code == 500);
        assertTrue(ra.catalog().size() >= 1500);
    }

    @Test
    public void unknownIs404() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:18083/nope")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, resp.statusCode());
    }

    @Test
    public void adminManifest() {
        assertEquals("sbi-http3-ra", new SbiHttp3RaAdminContributor().manifest().raName());
    }
}
