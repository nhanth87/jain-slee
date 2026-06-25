package com.microjainslee.api.annotations;

import java.lang.annotation.*;

/**
 * JAIN-SLEE 1.1 §5.1 — Event Type annotation.
 * Marks an event class.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface EventType {
    String name();
    String vendor() default "com.microjainslee";
    String version() default "1.0";
}