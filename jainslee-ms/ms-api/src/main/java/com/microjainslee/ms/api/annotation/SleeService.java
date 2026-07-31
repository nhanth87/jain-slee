/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.api.annotation;

import com.microjainslee.ms.api.TransportType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a deployable microservice boundary (typically an RA type).
 * Sole source of dependency-graph metadata for the ms orchestrator.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SleeService {

    /** Logical service name used in deployment.yml and dependency edges. */
    String name();

    /** Preferred remote transport. Default is Infinispan queue. */
    TransportType transport() default TransportType.INFINISPAN_QUEUE;

    /** Hard dependencies that must be READY before this service starts. */
    String[] dependsOn() default {};

    /** Soft dependencies — absent/unready does not block startup. */
    String[] optionalDeps() default {};

    /** Lower values start first when the DAG has ties. */
    int startPriority() default 100;

    /** Max wait for each hard dependency to become READY. */
    long startupTimeoutMs() default 30_000L;
}
