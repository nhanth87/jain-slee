package com.microjainslee.ra.jss7.collab;

import org.junit.Test;
import org.restcomm.protocols.ss7.map.api.smstpdu.CharacterSet;
import org.restcomm.protocols.ss7.map.datacoding.CBSDataCodingSchemeImpl;
import org.restcomm.protocols.ss7.map.primitives.USSDStringImpl;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** USSD string coding on the MAP wire: GSM-7 / 8-bit / UCS-2 within 160 octets (TS 29.002). */
public class MapUssdOutboundCodingTest {

    private static final CBSDataCodingSchemeImpl GSM7 = new CBSDataCodingSchemeImpl(0x0F);
    private static final CBSDataCodingSchemeImpl GSM8 = new CBSDataCodingSchemeImpl(0x44);
    private static final CBSDataCodingSchemeImpl UCS2 = new CBSDataCodingSchemeImpl(0x48);

    @Test
    public void dcsGroupsAreWhatWeThinkTheyAre() {
        assertEquals(CharacterSet.GSM7, GSM7.getCharacterSet());
        assertEquals(CharacterSet.GSM8, GSM8.getCharacterSet());
        assertEquals(CharacterSet.UCS2, UCS2.getCharacterSet());
    }

    @Test
    public void gsm8EncodesWithItsCharsetInsteadOfThrowing() throws Exception {
        String latin = "Café ÿ";
        byte[] wire = MapUssdOutbound.ussdString(latin, GSM8).getEncodedString();
        assertEquals(latin, new String(wire, StandardCharsets.ISO_8859_1));
    }

    @Test
    public void ucs2IsCutAt80UnitsNot160Characters() throws Exception {
        String amharic = "ሀ".repeat(150);
        String fitted = MapUssdOutbound.fitUssd(amharic, UCS2);
        assertEquals(80, fitted.length());
        assertEquals(160, MapUssdOutbound.ussdString(amharic, UCS2).getEncodedString().length);
    }

    @Test
    public void gsm7KeepsAll182Septets() throws Exception {
        String text = "A".repeat(200);
        assertEquals(182, MapUssdOutbound.fitUssd(text, GSM7).length());
        byte[] wire = MapUssdOutbound.ussdString(text, GSM7).getEncodedString();
        assertTrue(wire.length <= MapUssdOutbound.MAX_USSD_OCTETS);
        assertEquals("A".repeat(182), new USSDStringImpl(wire, GSM7).getString(null));
    }

    @Test
    public void shortTextIsUntouched() {
        assertEquals("*101#", MapUssdOutbound.fitUssd("*101#", GSM7));
        assertEquals("", MapUssdOutbound.fitUssd(null, GSM7));
    }

    @Test
    public void ucs2CutNeverSplitsASurrogatePair() {
        String text = "a".repeat(79) + "😀" + "b";
        String fitted = MapUssdOutbound.fitUssd(text, UCS2);
        assertFalse(Character.isHighSurrogate(fitted.charAt(fitted.length() - 1)));
        assertEquals(79, fitted.length());
    }
}
