package com.seloomwarp.vpn;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Removes private/local address ranges from default VPN routes.
 *
 * Splitting 0/0 into two /1 routes is not a LAN bypass: it still captures every
 * RFC1918/ULA destination. This class subtracts the local-use CIDRs while
 * preserving the address families explicitly present in AllowedIPs.
 */
final class VpnRoutePolicy {
    private static final List<String> IPV4_LAN = Arrays.asList(
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16");
    private static final List<String> IPV6_LAN = Arrays.asList(
            "fc00::/7", "fe80::/10");

    private VpnRoutePolicy() {}

    static String applyLanBypass(String allowedIps, boolean bypassLan) {
        if (allowedIps == null || allowedIps.trim().isEmpty() || !bypassLan) {
            return allowedIps == null ? "" : allowedIps.trim();
        }
        List<String> result = new ArrayList<>();
        for (String raw : allowedIps.split(",")) {
            String route = raw.trim();
            if (route.isEmpty()) continue;
            try {
                int bits = addressBits(route);
                if (("0.0.0.0/0".equals(route) && bits == 32)
                        || ("::/0".equalsIgnoreCase(route) && bits == 128)) {
                    List<String> pieces = new ArrayList<>();
                    pieces.add(route);
                    List<String> exclusions = bits == 32 ? IPV4_LAN : IPV6_LAN;
                    for (String exclusion : exclusions) {
                        List<String> next = new ArrayList<>();
                        for (String piece : pieces) next.addAll(subtract(piece, exclusion));
                        pieces = next;
                    }
                    result.addAll(pieces);
                } else {
                    result.add(route);
                }
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
            BigInteger network = new BigInteger(1, address).and(mask);
            return new Cidr(network, prefix, bits);
        }
    }
}
