package com.acquira.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpTest {

    private static final String REMOTE = "10.0.0.7";

    @Test
    void noHeaderFallsBackToRemoteAddr() {
        assertEquals(REMOTE, ClientIp.resolve(null, REMOTE, 1));
        assertEquals(REMOTE, ClientIp.resolve("  ", REMOTE, 1));
    }

    @Test
    void singleProxyTakesTheEntryItAppended() {
        assertEquals("203.0.113.9", ClientIp.resolve("203.0.113.9", REMOTE, 1));
        // a forged left-hand entry must not win
        assertEquals("203.0.113.9", ClientIp.resolve("1.2.3.4, 203.0.113.9", REMOTE, 1));
    }

    @Test
    void twoProxiesSkipTheInnerOne() {
        assertEquals("203.0.113.9", ClientIp.resolve("1.2.3.4, 203.0.113.9, 10.1.1.1", REMOTE, 2));
        // fewer entries than proxies: take what is there
        assertEquals("203.0.113.9", ClientIp.resolve("203.0.113.9", REMOTE, 2));
    }

    @Test
    void zeroProxiesIgnoresTheHeader() {
        assertEquals(REMOTE, ClientIp.resolve("1.2.3.4", REMOTE, 0));
    }

    @Test
    void crlfAndOtherNonAddressCharsAreStripped() {
        // log-injection attempt: forged line after the address
        // (hex letters are legal IPv6 chars, so the forged text here deliberately avoids a-f)
        assertEquals("203.0.113.9", ClientIp.resolve("203.0.113.9\r\nroot login OK", REMOTE, 1));
        assertEquals("203.0.113.9", ClientIp.resolve("203.0.113.9 <!>", REMOTE, 1));
        // legal IPv6 forms survive intact
        assertEquals("[2001:db8::1]:8443", ClientIp.resolve("[2001:db8::1]:8443", REMOTE, 1));
        assertEquals("fe80::1%25", ClientIp.resolve("fe80::1%25", REMOTE, 1));
    }

    @Test
    void garbageOnlyOrOverlongHopFallsBackOrIsCapped() {
        assertEquals(REMOTE, ClientIp.resolve("\r\n\t;;", REMOTE, 1));
        String longHop = "1".repeat(200);
        assertEquals(64, ClientIp.resolve(longHop, REMOTE, 1).length());
    }
}
