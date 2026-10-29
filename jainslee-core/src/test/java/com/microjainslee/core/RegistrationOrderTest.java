package com.microjainslee.core;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class RegistrationOrderTest {

    private static List<String> ids(MicroSleeContainer.RegistrationOrder order) {
        List<String> out = new ArrayList<>();
        order.forEach(out::add);
        return out;
    }

    @Test
    public void keepsFirstRegistrationOrderAndIgnoresDuplicates() {
        MicroSleeContainer.RegistrationOrder order = new MicroSleeContainer.RegistrationOrder();
        order.addIfAbsent("a");
        order.addIfAbsent("b");
        order.addIfAbsent("a");
        order.addIfAbsent("c");

        assertEquals(List.of("a", "b", "c"), ids(order));
        assertEquals(3, order.size());
    }

    @Test
    public void removedIdsAreGoneAndReAddGoesToTheEnd() {
        MicroSleeContainer.RegistrationOrder order = new MicroSleeContainer.RegistrationOrder();
        order.addIfAbsent("a");
        order.addIfAbsent("b");
        order.remove("a");
        order.remove("missing");
        order.addIfAbsent("a");

        assertEquals(List.of("b", "a"), ids(order));
    }

    @Test
    public void removeDuringIterationIsSafe() {
        MicroSleeContainer.RegistrationOrder order = new MicroSleeContainer.RegistrationOrder();
        for (int i = 0; i < 1_000; i++) {
            order.addIfAbsent("e" + i);
        }
        for (String id : order) {
            order.remove(id);
        }
        assertEquals(0, order.size());
        assertEquals(List.of(), ids(order));
    }
}
