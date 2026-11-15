package com.microjainslee.ra.httpclient.cookie;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class CookieJarTest {

    @Test
    public void keepsAndReplacesCookies() {
        assertEquals("JSESSIONID=1", CookieJar.merge(null, List.of("JSESSIONID=1; Path=/; HttpOnly")));
        assertEquals("JSESSIONID=2; route=a",
                CookieJar.merge("JSESSIONID=1", List.of("JSESSIONID=2", "route=a")));
        assertEquals("a=1", CookieJar.merge("a=1", null));
        assertEquals("q=quoted", CookieJar.merge(null, List.of("q=\"quoted\"")));
    }

    @Test
    public void dropsCookiesTheServerDeletes() {
        assertEquals("a=b", CookieJar.merge("JSESSIONID=1; a=b", List.of("JSESSIONID=; Max-Age=0")));
        assertNull(CookieJar.merge("a=b", List.of("a=x; Expires=Thu, 01 Jan 1970 00:00:00 GMT")));
        // Max-Age wins over an old Expires, whatever the order.
        assertEquals("a=x", CookieJar.merge(null,
                List.of("a=x; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=60")));
    }
}
