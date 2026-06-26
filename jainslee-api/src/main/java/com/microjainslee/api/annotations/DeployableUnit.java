/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.api.annotations;

import java.lang.annotation.*;

/**
 * JAIN-SLEE 1.1 §13.2 — Deployable Unit annotation.
 * Marks a module as a Deployable Unit.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PACKAGE, ElementType.TYPE})
@Documented
public @interface DeployableUnit {
    String name();
    String vendor() default "com.microjainslee";
    String version() default "1.0";
    Class<?>[] sbbs() default {};
    Class<?>[] ras() default {};
    Class<?>[] profileSpecs() default {};
    String description() default "";
}