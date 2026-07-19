package com.example.helloworld.quarkus.telemetry;

import com.example.helloworld.quarkus.http.HttpReply;
import com.example.helloworld.quarkus.http.MonitorHandler;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointHitStoreTest {

    @Test
    void recordsPerMethodAndPathAndExposesViaTelemetryApi() {
        EndpointHitStore store = new EndpointHitStore();
        store.record("GET", "/");
        store.record("GET", "/");
        store.record("GET", "/api/telemetry/endpoints");
        store.record("post", "/health"); // method normalized

        assertEquals(2L, store.snapshot().get("GET /"));
        assertEquals(1L, store.snapshot().get("GET /api/telemetry/endpoints"));
        assertEquals(1L, store.snapshot().get("POST /health"));
        assertEquals(4L, store.totalHits());

        MonitorHandler monitor = new MonitorHandler(null, store);
        HttpReply reply = monitor.handle(new HttpWebRequestEvent(
                "s1", "GET", "/api/telemetry/endpoints", Map.of(), null)).orElseThrow();
        assertEquals(200, reply.status());
        assertTrue(reply.text().contains("\"GET /\":2") || reply.text().contains("\"GET /\": 2"));
        assertTrue(reply.text().contains("\"total\":4") || reply.text().contains("\"total\": 4"));
    }
}
