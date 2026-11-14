package com.seloomwarp.vpn;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Removes actual on-device local networks from VPN routes.
 * Do not exclude all RFC1918 space: remote corporate/private networks may
 * intentionally be reachable through the VPN.
 */
final class VpnRoutePolicy {
    private static final List<String> LINK_LOCAL = Arrays.asList(
            "169.254.0.0/16", "fe80::/10");

    private VpnRoutePolicy() {}

    static List<String> lanExclusionCidrs(List<String> localNetworks) {
        LinkedHashSet<String> result = new LinkedHashSet<>(LINK_LOCAL);
        if (localNetworks != null) {
            for (String route : localNetworks) {
                if (route != null && !route.trim().isEmpty()) result.add(route.trim());
            }
        }
        return new ArrayList<>(result);
    }

    static String applyLanBypass(String allowedIps, boolean bypassLan) {
        return applyLanBypass(allowedIps, bypassLan, null);
    }

    static String applyLanBypass(String allowedIps, boolean bypassLan, List<String> localNetworks) {
        if (allowedIps == null || allowedIps.trim().isEmpty() || !bypassLan) {
            return allowedIps == null ? "" : allowedIps.trim();
        }
        List<String> exclusions = lanExclusionCidrs(localNetworks);
        List<String> result = new ArrayList<>();
        for (String raw : allowedIps.split(",")) {
            String route = raw.trim();
            if (route.isEmpty()) continue;
            try {
                int bits = addressBits(route);
                if (bits != 32 && bits != 128) {
                    result.add(route);
                    continue;
                }
                List<String> pieces = new ArrayList<>();
                pieces.add(route);
                for (String exclusion : exclusions) {
                    List<String> next = new ArrayList<>();
                    for (String piece : pieces) next.addAll(subtract(piece, exclusion));
                    pieces = next;
                    if (pieces.isEmpty()) break;
                }
                result.addAll(pieces);
            } catch (Exception invalidRoute) {
                // Preserve the caller's validation/error path for malformed CIDRs.
                result.add(route);
            }
        }
        return String.join(",", result);
    }

    private static int addressBits(String cidr) throws Exception {
        String[] parts = cidr.split("/", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid CIDR");
        return InetAddress.getByName(parts[0]).getAddress().length * 8;
    }

    private static List<String> subtract(String base, String excluded) throws Exception {
        Cidr a = Cidr.parse(base);
        Cidr b = Cidr.parse(excluded);
        if (a.bits != b.bits) return Arrays.asList(base);
        List<String> output = new ArrayList<>();
        subtract(a.network, a.prefix, a.bits, b, output);
        return output;
    }

    private static void subtract(BigInteger network, int prefix, int bits, Cidr excluded,
                                 List<String> output) throws Exception {
        BigInteger size = BigInteger.ONE.shiftLeft(bits - prefix);
        BigInteger end = network.add(size).subtract(BigInteger.ONE);
        BigInteger excludedEnd = excluded.network.add(
                BigInteger.ONE.shiftLeft(bits - excluded.prefix)).subtract(BigInteger.ONE);
        if (excludedEnd.compareTo(network) < 0 || excluded.network.compareTo(end) > 0) {
            output.add(format(network, prefix, bits));
            return;
        }
        if (excluded.network.compareTo(network) <= 0 && excludedEnd.compareTo(end) >= 0) return;
        if (prefix >= bits) return;
        int childPrefix = prefix + 1;
        BigInteger half = BigInteger.ONE.shiftLeft(bits - childPrefix);
        subtract(network, childPrefix, bits, excluded, output);
        subtract(network.add(half), childPrefix, bits, excluded, output);
    }

    private static String format(BigInteger network, int prefix, int bits) throws Exception {
        int bytes = bits / 8;
        byte[] raw = network.toByteArray();
        byte[] address = new byte[bytes];
        int sourceOffset = Math.max(0, raw.length - bytes);
        int length = Math.min(raw.length, bytes);
        System.arraycopy(raw, sourceOffset, address, bytes - length, length);
        return InetAddress.getByAddress(address).getHostAddress() + "/" + prefix;
    }

    private static final class Cidr {
        final BigInteger network;
        final int prefix;
        final int bits;

        Cidr(BigInteger network, int prefix, int bits) {
            this.network = network;
            this.prefix = prefix;
            this.bits = bits;
        }

        static Cidr parse(String value) throws Exception {
            String[] parts = value.split("/", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid CIDR: " + value);
            byte[] address = InetAddress.getByName(parts[0]).getAddress();
            int bits = address.length * 8;
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > bits) throw new IllegalArgumentException("Invalid prefix");
            BigInteger mask = prefix == 0 ? BigInteger.ZERO
                    : BigInteger.ONE.shiftLeft(prefix).subtract(BigInteger.ONE).shiftLeft(bits - prefix);
            return new Cidr(new BigInteger(1, address).and(mask), prefix, bits);
        }
    }
}
