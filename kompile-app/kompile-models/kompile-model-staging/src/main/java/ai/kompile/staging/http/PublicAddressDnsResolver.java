/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.staging.http;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * DNS resolver that returns only globally routable addresses.
 *
 * <p>Validation happens inside Apache HttpClient's connection manager. The
 * exact address array returned here is therefore the array used to establish
 * the socket, avoiding a validate-then-resolve DNS rebinding window.</p>
 */
public final class PublicAddressDnsResolver implements DnsResolver {

    private final DnsResolver delegate;
    private final boolean allowLoopbackForTests;

    public PublicAddressDnsResolver() {
        this(SystemDefaultDnsResolver.INSTANCE, false);
    }

    PublicAddressDnsResolver(
            DnsResolver delegate, boolean allowLoopbackForTests) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.allowLoopbackForTests = allowLoopbackForTests;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = delegate.resolve(host);
        if (addresses == null || addresses.length == 0) {
            throw rejected();
        }
        InetAddress[] validated = addresses.clone();
        for (InetAddress address : validated) {
            if (address == null || !isAllowed(address)) {
                throw rejected();
            }
        }
        return validated;
    }

    @Override
    public String resolveCanonicalHostname(String host)
            throws UnknownHostException {
        resolve(host);
        return host;
    }

    private boolean isAllowed(InetAddress address) {
        if (allowLoopbackForTests && address.isLoopbackAddress()) {
            return true;
        }
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet4Address) {
            return isPublicIpv4(address.getAddress());
        }
        if (address instanceof Inet6Address) {
            return isPublicIpv6(address.getAddress());
        }
        return false;
    }

    static boolean isPublicIpv4(byte[] bytes) {
        if (bytes == null || bytes.length != 4) {
            return false;
        }
        int a = bytes[0] & 0xff;
        int b = bytes[1] & 0xff;
        int c = bytes[2] & 0xff;

        return a != 0
                && a != 10
                && a != 127
                && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 169 && b == 254)
                && !(a == 172 && b >= 16 && b <= 31)
                && !(a == 192 && b == 0 && c == 0)
                && !(a == 192 && b == 0 && c == 2)
                && !(a == 192 && b == 88 && c == 99)
                && !(a == 192 && b == 168)
                && !(a == 198 && (b == 18 || b == 19))
                && !(a == 198 && b == 51 && c == 100)
                && !(a == 203 && b == 0 && c == 113)
                && a < 224;
    }

    static boolean isPublicIpv6(byte[] bytes) {
        if (bytes == null || bytes.length != 16) {
            return false;
        }

        if (isIpv4Mapped(bytes)) {
            return isPublicIpv4(new byte[] {
                    bytes[12], bytes[13], bytes[14], bytes[15]
            });
        }

        // Current global-unicast allocation is 2000::/3.
        int first = bytes[0] & 0xff;
        if (first < 0x20 || first > 0x3f) {
            return false;
        }

        // IETF protocol assignments and transition mechanisms (2001::/23).
        if (first == 0x20
                && (bytes[1] & 0xff) == 0x01
                && ((bytes[2] & 0xfe) == 0)) {
            return false;
        }

        // Documentation-only 2001:db8::/32.
        if (first == 0x20
                && (bytes[1] & 0xff) == 0x01
                && (bytes[2] & 0xff) == 0x0d
                && (bytes[3] & 0xff) == 0xb8) {
            return false;
        }

        // Deprecated 6to4 can tunnel to an embedded non-public IPv4 target.
        if (first == 0x20 && (bytes[1] & 0xff) == 0x02) {
            return false;
        }

        // Documentation-only 3fff::/20.
        return !(first == 0x3f && ((bytes[1] & 0xf0) == 0xf0));
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xff) == 0xff
                && (bytes[11] & 0xff) == 0xff;
    }

    private static UnknownHostException rejected() {
        return new UnknownHostException(
                "Remote host did not resolve exclusively to public addresses");
    }
}
