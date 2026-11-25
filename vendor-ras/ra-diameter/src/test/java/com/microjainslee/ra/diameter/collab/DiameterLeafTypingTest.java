package com.microjainslee.ra.diameter.collab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.microjainslee.ra.diameter.avp.DiameterAvp;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Seen on the Gx wire: CC-Request-Type (Enumerated) and Framed-IP-Address (4 octets) arrived as
 * strings of control characters because every leaf was read with JDiameter's lenient
 * {@code getUTF8String}; the PCRF never saw the request type or the UE address.
 */
public class DiameterLeafTypingTest {

    @Test
    public void printableUtf8StaysAString() {
        var leaf = DiameterEventClassifier.leafOf("internet".getBytes(StandardCharsets.UTF_8));
        assertEquals("internet", leaf.utf8());
    }

    @Test
    public void fourOctetNumberIsUnsigned32NotAString() {
        var leaf = DiameterEventClassifier.leafOf(new byte[] {0, 0, 0, 1});
        assertNull(leaf.utf8());
        assertEquals(Long.valueOf(1L), leaf.u32());
        assertEquals(Long.valueOf(2001L), DiameterEventClassifier.leafOf(new byte[] {0, 0, 0x07, (byte) 0xd1}).u32());
    }

    @Test
    public void addressOctetsSurviveThroughTheRawValue() {
        byte[] ip = {10, 64, 0, 2};
        DiameterAvp avp = DiameterAvp.received(8, 0, true, DiameterAvp.Type.UNSIGNED32,
                DiameterEventClassifier.leafOf(ip).u32(), ip);
        assertArrayEquals(ip, avp.asOctets());
        assertEquals(0x0a400002L, avp.asUnsigned32());
    }

    @Test
    public void nonUtf8OctetsStayOctets() {
        byte[] v = {(byte) 0xc0, 0x00, 0x02, 0x0e, 0x01};
        var leaf = DiameterEventClassifier.leafOf(v);
        assertNull(leaf.utf8());
        assertNull(leaf.u32());
        assertArrayEquals(v, leaf.octets());
    }
}
