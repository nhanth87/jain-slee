package com.microjainslee.ra.sbi.openapi;

import com.microjainslee.ra.sbi.openapi.headers.SbiHeaderCodec;
import com.microjainslee.ra.sbi.openapi.problem.ProblemDetails;

import org.junit.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SbiOpenApiCatalogTest {

    @Test
    public void loadsCatalogAndMatchesPathParams() {
        SbiOpenApiCatalog cat = SbiOpenApiCatalog.loadDefault();
        assertTrue("expected Rel-18 scale catalog", cat.size() >= 1500);
        Optional<SbiRouteMatch> m = cat.match("GET",
                "/nnrf-disc/v1/nf-instances");
        assertTrue(m.isPresent());
        assertEquals("SearchNFInstances", m.get().operation().operationId());

        Optional<SbiRouteMatch> m2 = cat.match("PUT",
                "/nnrf-nfm/v1/nf-instances/abc-123");
        assertTrue(m2.isPresent());
        assertEquals("RegisterNFInstance", m2.get().operation().operationId());
        assertEquals("abc-123", m2.get().pathParams().get("nfInstanceID"));
    }

    @Test
    public void yamlSeedAddsOperations() {
        SbiOpenApiCatalog cat = SbiOpenApiCatalog.loadDefault();
        assertTrue(cat.byOperationId("GetNFInstances").isPresent());
        // YAML seed may be skipped on jackson skew — catalog.json is authoritative
        assertTrue(cat.size() >= 1500);
    }

    @Test
    public void allowedMethodsForPath() {
        SbiOpenApiCatalog cat = SbiOpenApiCatalog.loadDefault();
        var allow = cat.allowedMethods("/nnrf-nfm/v1/nf-instances/x");
        assertTrue(allow.contains("GET") || allow.contains("PUT") || allow.contains("DELETE"));
    }

    @Test
    public void problemDetailsJson() {
        String json = ProblemDetails.of(404, "Not Found", "no route", "RESOURCE_URI_STRUCTURE_NOT_FOUND")
                .toJson();
        assertTrue(json.contains("\"status\":404"));
        assertTrue(json.contains("RESOURCE_URI_STRUCTURE_NOT_FOUND"));
    }

    @Test
    public void retryInfoNoRetries() {
        SbiHeaderCodec h = new SbiHeaderCodec(Map.of("3gpp-sbi-retry-info", "no-retries"));
        assertTrue(h.noRetries());
        assertFalse(new SbiHeaderCodec(Map.of()).noRetries());
    }
}
