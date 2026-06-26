/*
 * micro-jainslee 1.1.0 — example application (ussd-quarkus-demo)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class UssdDemoResourceTest {

    @Test
    void ussdBeginCompletesThroughBothSbbs() throws InterruptedException {
        String sessionId = given()
                .contentType(ContentType.JSON)
                .body("{\"msisdn\":\"251911000001\",\"ussdString\":\"*123#\"}")
                .when()
                .post("/api/ussd/begin")
                .then()
                .statusCode(202)
                .body("sessionId", notNullValue())
                .body("status", equalTo("PROCESSING"))
                .extract()
                .path("sessionId");

        long deadline = System.nanoTime() + 10_000_000_000L;
        String status = "PROCESSING";
        while (System.nanoTime() < deadline && "PROCESSING".equals(status)) {
            status = given()
                    .when()
                    .get("/api/ussd/sessions/" + sessionId)
                    .then()
                    .statusCode(200)
                    .extract()
                    .path("status");
            Thread.sleep(50L);
        }

        assertTrue("COMPLETED".equals(status), "expected COMPLETED but was " + status);
        given()
                .when()
                .get("/api/ussd/sessions/" + sessionId)
                .then()
                .statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("responseText", notNullValue());
    }
}
