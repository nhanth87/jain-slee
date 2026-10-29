package com.microjainslee.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** O(1) {@code resolveName} must answer exactly what the old full scan answered. */
public class NamingFacilityReverseIndexTest {

    private final InMemoryActivityContextNamingFacility acnf = new InMemoryActivityContextNamingFacility();

    @Test
    public void bindResolveUnbind() {
        InMemoryActivityContext a = new InMemoryActivityContext("__pool__");
        acnf.bind("call-1", a);
        assertEquals("call-1", acnf.resolveName(a));
        acnf.unbind("call-1");
        assertNull(acnf.resolveName(a));
        assertNull(acnf.resolveName(null));
    }

    @Test
    public void reboundNameNoLongerResolvesTheOldContext() {
        InMemoryActivityContext a = new InMemoryActivityContext("a");
        InMemoryActivityContext b = new InMemoryActivityContext("b");
        acnf.bind("x", a);
        acnf.bind("x", b);
        assertNull(acnf.resolveName(a));
        assertEquals("x", acnf.resolveName(b));
    }

    @Test
    public void contextBoundUnderSeveralNamesKeepsAnotherNameOnUnbind() {
        InMemoryActivityContext a = new InMemoryActivityContext("a");
        acnf.bind("n1", a);
        acnf.bind("n2", a);
        assertEquals("n2", acnf.resolveName(a));
        acnf.unbind("n2");
        assertEquals("n1", acnf.resolveName(a));
        acnf.bind("n3", a);
        acnf.unbind("n1"); // not the indexed key: index keeps n3
        assertEquals("n3", acnf.resolveName(a));
        acnf.unbind("n3");
        assertNull(acnf.resolveName(a));
    }

    @Test
    public void clearDropsEverything() {
        InMemoryActivityContext a = new InMemoryActivityContext("a");
        acnf.bind("n", a);
        acnf.clear();
        assertNull(acnf.resolveName(a));
        assertTrue(acnf.getBoundContexts().isEmpty());
    }
}
