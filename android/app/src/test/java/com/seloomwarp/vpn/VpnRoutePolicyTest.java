package com.seloomwarp.vpn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VpnRoutePolicyTest {
    @Test
    public void ipv4DefaultExcludesPrivateAndLinkLocalNetworks() {
        String routes = VpnRoutePolicy.applyLanBypass("0.0.0.0/0", true);
        assertFalse(contains(routes, "10.1.2.3"));
        assertFalse(contains(routes, "172.20.1.2"));
        assertFalse(contains(routes, "192.168.1.1"));
        assertFalse(contains(routes, "169.254.10.1"));
        assertTrue(contains(routes, "8.8.8.8"));
        assertTrue(contains(routes, "100.64.1.1"));
    }

    @Test
    public void ipv6DefaultExcludesUlaAndLinkLocalButPreservesGlobal() {
        String routes = VpnRoutePolicy.applyLanBypass("::/0", true);
        assertFalse(contains(routes, "fc00::1"));
        assertFalse(contains(routes, "fd12:3456::1"));
        assertFalse(contains(routes, "fe80::1234"));
        assertTrue(contains(routes, "2001:4860:4860::8888"));
    }

    @Test
    public void onlyExplicitlyPresentAddressFamiliesAreChanged() {
        String routes = VpnRoutePolicy.applyLanBypass("0.0.0.0/0", true);
        assertFalse(routes.contains(":"));
        assertTrue(contains(routes, "1.1.1.1"));
    }

    @Test
    public void nonDefaultRoutesArePreserved() {
        assertEquals("2001:db8::/32",
                VpnRoutePolicy.applyLanBypass("10.0.0.0/8,2001:db8::/32", true));
        assertEquals("203.0.113.0/24",
                VpnRoutePolicy.applyLanBypass("203.0.113.0/24", true));
    }

    @Test
    public void disabledBypassLeavesDefaultsUntouched() {
        assertEquals("0.0.0.0/0,::/0",
                VpnRoutePolicy.applyLanBypass("0.0.0.0/0,::/0", false));
    }

    private static boolean contains(String routes, String address) {
        try {
            java.net.InetAddress target = java.net.InetAddress.getByName(address);
            java.math.BigInteger value = new java.math.BigInteger(1, target.getAddress());
            for (String route : routes.split(",")) {
                String[] parts = route.split("/");
                byte[] networkBytes = java.net.InetAddress.getByName(parts[0]).getAddress();
                if (networkBytes.length != target.getAddress().length) continue;
                int bits = Integer.parseInt(parts[1]);
                java.math.BigInteger mask = bits == 0 ? java.math.BigInteger.ZERO
                        : java.math.BigInteger.ONE.shiftLeft(bits).subtract(java.math.BigInteger.ONE)
                                .shiftLeft(networkBytes.length * 8 - bits);
                java.math.BigInteger network = new java.math.BigInteger(1, networkBytes).and(mask);
                if (value.and(mask).equals(network)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }
}
