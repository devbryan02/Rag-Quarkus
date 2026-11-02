package com.bryan.features.chat.web;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TrustedProxyAddressTest {
    @Test void trustsForwardedAddressOnlyFromTheConfiguredProxy() {
        assertEquals("198.51.100.10",TrustedProxyAddress.select("172.20.0.4","198.51.100.10",Set.of("172.20.0.4")));
        assertEquals("198.51.100.5",TrustedProxyAddress.select("198.51.100.5","forged.example",Set.of("172.20.0.4")));
        assertEquals("172.20.0.4",TrustedProxyAddress.select("172.20.0.4","not-an-address",Set.of("172.20.0.4")));
    }
}
