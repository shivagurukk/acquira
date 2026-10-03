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
}
