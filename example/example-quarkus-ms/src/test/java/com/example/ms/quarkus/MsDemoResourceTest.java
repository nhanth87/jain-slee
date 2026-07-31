/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ms.quarkus;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class MsDemoResourceTest {

    @Test
    void healthIsUpInSingleMode() {
        given()
                .when().get("/api/health")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("mode", equalTo("SINGLE"))
                .body("local.signaling", is(true))
                .body("local.app", is(true));
    }

    @Test
    void callSignalingPing() {
        given()
                .contentType("text/plain")
                .body("")
                .when().post("/api/demo/call-signaling?op=ping")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("payload", equalTo("pong"))
                .body("viaLocal", is(true));
    }
}
