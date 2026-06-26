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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * JAIN-SLEE 1.1 §6.5 — CMP field annotation.
 * <p>
 * Marks an abstract getter or setter on the SBB abstract class as a
 * Container-Managed Persistence field. The {@link #value()} is the
 * logical name of the CMP attribute as visible in the deployment
 * descriptor; {@link #indexed()} requests an index when the underlying
 * store supports it.
 *
 * @author Tran Nhan (nhanth87)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface CmpField {

    /**
     * Logical name of the CMP field. Must be a valid JAIN SLEE identifier
     * (see JAIN-SLEE 1.1 §6.5).
     *
     * @return the CMP field name
     */
    String value();

    /**
     * Whether the underlying store should build an index on this field.
     *
     * @return {@code true} to request an indexed CMP field
     */
    boolean indexed() default false;
}