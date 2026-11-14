package com.seloomwarp.vpn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

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

    @Test public void rejectsMalformedVlessUuid() {
        assertThrows(IllegalArgumentException.class,
                () -> SingBoxProfileParser.parse("vless://not-a-uuid@example.com:443?encryption=none"));
    }

    @Test public void tunMtuUsesMobileSafeDefault() throws Exception {
        SingBoxProfileParser.Profile profile = SingBoxProfileParser.parse(
                "trojan://secret-pass@example.org:8443?security=tls");
        JSONObject tun = new JSONObject(profile.configJson).getJSONArray("inbounds").getJSONObject(0);
        assertEquals(1500, tun.getInt("mtu"));
    }

    @Test public void parsesVlessRealityWebsocketAndBuildsProxyRoute() throws Exception {
        SingBoxProfileParser.Profile profile = SingBoxProfileParser.parse(
                "vless://123e4567-e89b-12d3-a456-426614174000@edge.example.com:443"
                + "?encryption=none&security=reality&pbk=public-key&sid=abcd&sni=cdn.example.com"
                + "&fp=chrome&type=ws&host=cdn.example.com&path=%2Fgateway#Reality%20WS");

        assertEquals("vless", profile.protocol);
        assertEquals("Reality WS", profile.name);
        assertEquals("edge.example.com", profile.server);
        assertEquals(443, profile.port);

        JSONObject config = new JSONObject(profile.configJson);
        JSONArray outbounds = config.getJSONArray("outbounds");
        JSONObject proxy = outbounds.getJSONObject(0);
        assertEquals("vless", proxy.getString("type"));
        assertEquals("123e4567-e89b-12d3-a456-426614174000", proxy.getString("uuid"));
        assertEquals("cdn.example.com", proxy.getJSONObject("tls").getString("server_name"));
        assertTrue(proxy.getJSONObject("tls").getJSONObject("reality").getBoolean("enabled"));
        assertEquals("/gateway", proxy.getJSONObject("transport").getString("path"));
        assertEquals("proxy", config.getJSONObject("route").getString("final"));
        assertEquals("tun", config.getJSONArray("inbounds").getJSONObject(0).getString("type"));
    }

    @Test public void parsesTrojanTlsWithoutAddingEmptyTransport() throws Exception {
        SingBoxProfileParser.Profile profile = SingBoxProfileParser.parse(
                "trojan://secret-pass@example.org:8443?security=tls&sni=example.org#Trojan");
        JSONObject proxy = new JSONObject(profile.configJson).getJSONArray("outbounds").getJSONObject(0);
        assertEquals("trojan", proxy.getString("type"));
        assertEquals("secret-pass", proxy.getString("password"));
        assertFalse(proxy.has("transport"));
        assertTrue(proxy.getJSONObject("tls").getBoolean("enabled"));
    }

    @Test public void parsesVmessUrlSafeBase64WithoutPadding() throws Exception {
        String json = "{\"v\":\"2\",\"ps\":\"VMess Test\",\"add\":\"vmess.example.net\","
                + "\"port\":\"443\",\"id\":\"123e4567-e89b-12d3-a456-426614174000\","
                + "\"aid\":\"0\",\"scy\":\"auto\",\"net\":\"ws\",\"tls\":\"tls\","
                + "\"host\":\"cdn.example.net\",\"path\":\"/vmess\"}";
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        SingBoxProfileParser.Profile profile = SingBoxProfileParser.parse("vmess://" + encoded);
        JSONObject proxy = new JSONObject(profile.configJson).getJSONArray("outbounds").getJSONObject(0);
        assertEquals("vmess", proxy.getString("type"));
        assertEquals("VMess Test", profile.name);
        assertEquals("vmess.example.net", proxy.getString("server"));
        assertEquals("ws", proxy.getJSONObject("transport").getString("type"));
        assertTrue(proxy.getJSONObject("tls").getBoolean("enabled"));
    }
}
