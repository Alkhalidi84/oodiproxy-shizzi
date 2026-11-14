package com.seloomwarp.vpn;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VpnRoutePolicyTest {
    @Test
    public void ipv4OnlyPeerDoesNotGainIpv6Routes() {
        assertEquals(
            "0.0.0.0/1,128.0.0.0/1",
            VpnRoutePolicy.applyLanBypass("0.0.0.0/0", true)
        );
    }

    @Test
    public void dualStackPeerGetsSplitDefaultsForBothFamilies() {
        assertEquals(
            "0.0.0.0/1,128.0.0.0/1,::/1,8000::/1",
            VpnRoutePolicy.applyLanBypass("0.0.0.0/0,::/0", true)
        );
    }

    @Test
    public void nonDefaultRoutesArePreserved() {
        assertEquals(
            "10.0.0.0/8,2001:db8::/32",
            VpnRoutePolicy.applyLanBypass("10.0.0.0/8,2001:db8::/32", true)
        );
    }

    @Test
    public void disabledBypassLeavesDefaultsUntouched() {
        assertEquals(
            "0.0.0.0/0,::/0",
            VpnRoutePolicy.applyLanBypass("0.0.0.0/0,::/0", false)
        );
    }
}
