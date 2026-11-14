package com.seloomwarp.vpn;

import java.util.ArrayList;
import java.util.List;

/** Builds split-default routes without introducing an address family absent from the peer. */
final class VpnRoutePolicy {
    private VpnRoutePolicy() {}

    static String applyLanBypass(String allowedIps, boolean bypassLan) {
        if (allowedIps == null || allowedIps.trim().isEmpty() || !bypassLan) {
            return allowedIps == null ? "" : allowedIps.trim();
        }

        String[] routes = allowedIps.split(",");
        List<String> result = new ArrayList<>(routes.length + 2);
        for (String rawRoute : routes) {
            String route = rawRoute.trim();
            if (route.isEmpty()) continue;
            if ("0.0.0.0/0".equals(route)) {
                result.add("0.0.0.0/1");
                result.add("128.0.0.0/1");
            } else if ("::/0".equalsIgnoreCase(route)) {
                result.add("::/1");
                result.add("8000::/1");
            } else {
                result.add(route);
            }
        }
        return String.join(",", result);
    }
}
