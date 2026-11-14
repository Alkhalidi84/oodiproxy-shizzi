package com.seloomwarp.vpn;

import static org.junit.Assert.assertThrows;
import org.junit.Test;

public class SingBoxProfileParserTest {
    @Test public void rejectsUnsupportedSchemes() {
        assertThrows(IllegalArgumentException.class,
                () -> SingBoxProfileParser.parse("https://example.com"));
    }

    @Test public void rejectsMissingEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> SingBoxProfileParser.parse("vless://123e4567-e89b-12d3-a456-426614174000@example.com"));
    }

    @Test public void rejectsMissingCredentials() {
        assertThrows(IllegalArgumentException.class,
                () -> SingBoxProfileParser.parse("vless://example.com:443?encryption=none"));
    }

    @Test public void rejectsUnsupportedVlessEncryption() {
        assertThrows(IllegalArgumentException.class,
                () -> SingBoxProfileParser.parse("vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?encryption=aes"));
    }

    @Test public void rejectsRealityWithoutPublicKey() {
        assertThrows(IllegalArgumentException.class,
                () -> SingBoxProfileParser.parse("vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=reality"));
    }
}
