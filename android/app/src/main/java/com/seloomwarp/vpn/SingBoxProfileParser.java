package com.seloomwarp.vpn;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Converts common share links into a validated Sing-box configuration.
 *
 * This class deliberately does not start the native engine. The Android VPN
 * service must own the TUN descriptor and libbox CommandServer lifecycle.
 */
public final class SingBoxProfileParser {
    private SingBoxProfileParser() {}

    public static final class Profile {
        public final String protocol;
        public final String name;
        public final String server;
        public final int port;
        public final String configJson;

        Profile(String protocol, String name, String server, int port, String configJson) {
            this.protocol = protocol;
            this.name = name;
            this.server = server;
            this.port = port;
            this.configJson = configJson;
        }
    }

    public static Profile parse(String shareLink) throws JSONException {
        if (shareLink == null || shareLink.trim().isEmpty()) {
            throw new IllegalArgumentException("A proxy link is required");
        }
        String input = shareLink.trim();
        if (input.regionMatches(true, 0, "vmess://", 0, 8)) {
            return parseVmess(input.substring(8));
        }
        final URI uri;
        try {
            uri = URI.create(input);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid proxy link", error);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"vless".equals(scheme) && !"trojan".equals(scheme)) {
            throw new IllegalArgumentException("Supported links: VLESS, VMess and Trojan");
        }
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || host.isEmpty() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Proxy link must include a valid server and port");
        }
        Map<String, String> query = query(uri.getRawQuery());
        String tag = uri.getFragment() == null ? scheme + "-" + host : decode(uri.getFragment());
        String user = uri.getRawUserInfo() == null ? "" : decode(uri.getRawUserInfo());
        if (user.isEmpty()) throw new IllegalArgumentException("Proxy credentials are missing");
        JSONObject outbound = new JSONObject();
        outbound.put("type", scheme);
        outbound.put("tag", "proxy");
        outbound.put("server", host);
        outbound.put("server_port", port);
        if ("vless".equals(scheme)) {
            outbound.put("uuid", user);
            outbound.put("flow", value(query, "flow", ""));
            outbound.put("packet_encoding", "xudp");
            if (!"none".equalsIgnoreCase(value(query, "encryption", "none"))) {
                throw new IllegalArgumentException("VLESS encryption must be none");
            }
        } else {
            outbound.put("password", user);
        }
        applyTransportAndTls(outbound, query);
        return buildProfile(scheme, tag, host, port, outbound);
    }

    private static Profile parseVmess(String encoded) throws JSONException {
        String payload = encoded.trim();
        while (payload.length() % 4 != 0) payload += "=";
        final String decoded;
        try {
            decoded = new String(Base64.decode(payload, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid VMess base64 payload", error);
        }
        JSONObject source;
        try {
            source = new JSONObject(decoded);
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid VMess JSON payload", error);
        }
        String host = source.optString("add", "").trim();
        int port = parsePort(source.optString("port", ""));
        String uuid = source.optString("id", "").trim();
        if (host.isEmpty() || port == 0 || uuid.isEmpty()) {
            throw new IllegalArgumentException("VMess profile is missing server, port or UUID");
        }
        JSONObject outbound = new JSONObject();
        outbound.put("type", "vmess");
        outbound.put("tag", "proxy");
        outbound.put("server", host);
        outbound.put("server_port", port);
        outbound.put("uuid", uuid);
        outbound.put("security", source.optString("scy", "auto"));
        outbound.put("alter_id", Math.max(0, source.optInt("aid", 0)));
        Map<String, String> query = new LinkedHashMap<>();
        query.put("security", source.optString("tls", ""));
        query.put("sni", source.optString("sni", source.optString("host", "")));
        query.put("type", source.optString("net", "tcp"));
        query.put("path", source.optString("path", ""));
        query.put("host", source.optString("host", ""));
        query.put("serviceName", source.optString("path", ""));
        applyTransportAndTls(outbound, query);
        String name = source.optString("ps", "VMess-" + host);
        return buildProfile("vmess", name, host, port, outbound);
    }

    private static void applyTransportAndTls(JSONObject outbound, Map<String, String> q) throws JSONException {
        String security = value(q, "security", "none").toLowerCase(Locale.ROOT);
        if ("tls".equals(security) || "reality".equals(security)) {
            JSONObject tls = new JSONObject();
            tls.put("enabled", true);
            String sni = value(q, "sni", value(q, "serverName", ""));
            if (!sni.isEmpty()) tls.put("server_name", sni);
            String fingerprint = value(q, "fp", value(q, "fingerprint", ""));
            if (!fingerprint.isEmpty()) tls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", fingerprint));
            if ("reality".equals(security)) {
                JSONObject reality = new JSONObject();
                reality.put("enabled", true);
                String key = value(q, "pbk", value(q, "publicKey", ""));
                String sid = value(q, "sid", value(q, "shortId", ""));
                if (key.isEmpty()) throw new IllegalArgumentException("REALITY public key (pbk) is missing");
                reality.put("public_key", key);
                if (!sid.isEmpty()) reality.put("short_id", sid);
                tls.put("reality", reality);
            }
            outbound.put("tls", tls);
        } else if (!security.isEmpty() && !"none".equals(security) && !"".equals(security)) {
            throw new IllegalArgumentException("Unsupported TLS security mode: " + security);
        }

        String network = value(q, "type", "tcp").toLowerCase(Locale.ROOT);
        if ("ws".equals(network)) {
            JSONObject ws = new JSONObject();
            ws.put("type", "ws");
            String path = value(q, "path", "/");
            ws.put("path", path.isEmpty() ? "/" : path);
            String host = value(q, "host", "");
            if (!host.isEmpty()) ws.put("headers", new JSONObject().put("Host", host));
            outbound.put("transport", ws);
        } else if ("grpc".equals(network)) {
            JSONObject grpc = new JSONObject();
            grpc.put("type", "grpc");
            String serviceName = value(q, "serviceName", value(q, "service_name", ""));
            if (!serviceName.isEmpty()) grpc.put("service_name", serviceName);
            outbound.put("transport", grpc);
        } else if (!"tcp".equals(network) && !"http".equals(network) && !"h2".equals(network)) {
            throw new IllegalArgumentException("Unsupported transport: " + network);
        } else if ("http".equals(network) || "h2".equals(network)) {
            JSONObject http = new JSONObject();
            http.put("type", "http");
            String host = value(q, "host", "");
            if (!host.isEmpty()) http.put("host", new JSONArray().put(host));
            String path = value(q, "path", "");
            if (!path.isEmpty()) http.put("path", path);
            outbound.put("transport", http);
        }
    }

    private static Profile buildProfile(String protocol, String name, String host, int port, JSONObject proxy) throws JSONException {
        JSONObject direct = new JSONObject().put("type", "direct").put("tag", "direct");
        JSONObject block = new JSONObject().put("type", "block").put("tag", "block");
        JSONArray outbounds = new JSONArray().put(proxy).put(direct).put(block);
        JSONObject route = new JSONObject().put("final", "proxy").put("auto_detect_interface", true);
        JSONObject dns = new JSONObject()
                .put("servers", new JSONArray()
                        .put(new JSONObject().put("tag", "remote").put("address", "https://1.1.1.1/dns-query").put("detour", "proxy"))
                        .put(new JSONObject().put("tag", "local").put("address", "local")))
                .put("final", "remote");
        JSONObject config = new JSONObject()
                .put("log", new JSONObject().put("level", "info"))
                .put("dns", dns)
                .put("inbounds", new JSONArray().put(new JSONObject()
                        .put("type", "tun").put("tag", "tun-in")
                        .put("address", new JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
                        .put("mtu", 9000).put("auto_route", true).put("strict_route", true)
                        .put("stack", "system")) )
                .put("outbounds", outbounds)
                .put("route", route);
        return new Profile(protocol, name, host, port, config.toString());
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String pair : raw.split("&")) {
            int split = pair.indexOf('=');
            String key = decode(split < 0 ? pair : pair.substring(0, split));
            String val = decode(split < 0 ? "" : pair.substring(split + 1));
            result.put(key, val);
        }
        return result;
    }

    private static String value(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            return port >= 1 && port <= 65535 ? port : 0;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
