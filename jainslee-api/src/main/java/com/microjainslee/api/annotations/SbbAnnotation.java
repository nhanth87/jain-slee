package com.microjainslee.api.annotations;

import java.lang.annotation.*;

/**
 * JAIN-SLEE 1.1 §8.4 — SBB annotation.
 * Marks a class as an SBB.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface SbbAnnotation {
    String name();
    String vendor() default "com.microjainslee";
    String version() default "1.0";
}