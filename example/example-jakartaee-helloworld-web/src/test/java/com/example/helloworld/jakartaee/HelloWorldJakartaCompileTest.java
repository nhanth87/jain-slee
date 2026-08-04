/*
 * micro-jainslee 1.1.0 -- example application (example-jakartaee-helloworld-web)
 */

package com.example.helloworld.jakartaee;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Classpath sanity — no application server / WAR required.
 */
public final class HelloWorldJakartaCompileTest {

    @Test
    public void mainAndSbbClassesAreOnClasspath() throws Exception {
        Class<?> main = Class.forName("com.example.helloworld.jakartaee.HelloWorldMain");
        Class<?> sbb = Class.forName("com.example.helloworld.jakartaee.sbbs.HelloWorldSbb");
        assertNotNull(main);
        assertNotNull(sbb);
        assertTrue(main.getDeclaredMethod("main", String[].class) != null);
    }
}
