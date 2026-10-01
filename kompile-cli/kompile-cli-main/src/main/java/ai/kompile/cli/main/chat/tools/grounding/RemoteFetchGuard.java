/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Function;

/**
 * SSRF guard shared by every project-local remote fetch (single-URL documents and WEB_CRAWL).
 *
 * <p>Before each request the target host is resolved and every returned address is checked
 * against loopback/private/link-local/multicast/any-local ranges, including IPv4-mapped,
 * IPv4-compatible, and NAT64 (64:ff9b::/96, RFC 6052) IPv6 forms, the IPv6 unique-local fc00::/7
 * block (which the JDK does not classify as site-local), the carrier-grade NAT/shared address
 * space 100.64.0.0/10 (RFC 6598 &mdash; also used by Tailscale and similar overlay networks), and
 * the IPv4 "this network" block 0.0.0.0/8 (only the exact wildcard address 0.0.0.0 is covered by
 * {@link InetAddress#isAnyLocalAddress()}). The HTTP client never auto-follows redirects;
 * {@link #fetch} re-validates the {@code Location} of every hop itself, up to
 * {@link #MAX_REDIRECTS} times.
 *
 * <p>Private-network access is opt-in only via project configuration
 * ({@code crawl.allowPrivateNetworkUrls} in the project manifest metadata) &mdash; never a
 * request parameter, so an LLM or crawled page can never self-authorize access to internal
 * hosts. This class is package-private: tests in this package call {@link #fetch} and
 * {@link #validate} directly with an explicit {@code true} to reach a loopback fake server,
 * while every production call site threads the real project-config-derived value.
 */
final class RemoteFetchGuard {

    static final int MAX_REDIRECTS = 5;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private RemoteFetchGuard() {
    }

    /** Outcome of a guarded fetch: the open response body plus the final (post-redirect) URI. */
    record FetchResult(HttpResponse<InputStream> response, URI uri) {
    }

    /**
     * Sends {@code requestFactory}'s request to {@code seed}, following up to
     * {@link #MAX_REDIRECTS} redirects manually. {@code seed} and every redirect target are
     * validated by {@link #validate} before they are ever connected to.
     */
    static FetchResult fetch(URI seed,
                              Function<URI, HttpRequest> requestFactory,
                              boolean allowPrivateNetworkUrls) throws IOException, InterruptedException {
        URI current = seed;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            validate(current, allowPrivateNetworkUrls);
            HttpResponse<InputStream> response = CLIENT.send(
                    requestFactory.apply(current), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = response.headers().firstValue("Location").orElse(null);
                response.body().close();
                if (location == null || location.isBlank()) {
                    throw new IOException("Redirect from " + current + " had no Location header");
                }
                if (hop == MAX_REDIRECTS) {
                    throw new IOException("Exceeded " + MAX_REDIRECTS + " redirects fetching " + seed);
                }
                current = current.resolve(location);
                continue;
            }
            return new FetchResult(response, current);
        }
        throw new IOException("Exceeded " + MAX_REDIRECTS + " redirects fetching " + seed);
    }

    /**
     * Validates that {@code uri} uses http(s) and that none of its host's resolved addresses
     * fall in a blocked range, unless {@code allowPrivateNetworkUrls} opts in.
     */
    static void validate(URI uri, boolean allowPrivateNetworkUrls) throws IOException {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IOException("Only http and https URLs are allowed: " + uri);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("URL has no host: " + uri);
        }
        if (allowPrivateNetworkUrls) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException unresolvable) {
            throw new IOException("Cannot resolve host " + host + ": " + unresolvable.getMessage());
        }
        for (InetAddress address : addresses) {
            String blocked = blockedAddressClass(address);
            if (blocked != null) {
                throw new IOException("Refusing to fetch " + uri + ": host " + host + " resolves to "
                        + address.getHostAddress() + " (" + blocked + "). Set "
                        + "crawl.allowPrivateNetworkUrls=true in the project manifest metadata to "
                        + "allow private-network URLs for this project.");
            }
        }
    }

    /** Returns a human-readable blocked-class name, or {@code null} if {@code address} is public. */
    private static String blockedAddressClass(InetAddress address) {
        String direct = classify(address);
        if (direct != null) {
            return direct;
        }
        InetAddress unwrapped = unwrapMappedOrCompatible(address);
        if (unwrapped != null) {
            String indirect = classify(unwrapped);
            if (indirect != null) {
                return indirect + " via " + address.getHostAddress();
            }
        }
        return null;
    }

    private static String classify(InetAddress address) {
        if (address.isLoopbackAddress()) return "loopback";
        if (address.isAnyLocalAddress()) return "any-local";
        if (address.isLinkLocalAddress()) return "link-local";
        if (address.isSiteLocalAddress()) return "site-local/private";
        if (address.isMulticastAddress()) return "multicast";
        if (isThisNetworkIpv4(address)) return "this-network (0.0.0.0/8)";
        if (isCarrierGradeNatIpv4(address)) return "carrier-grade NAT/shared (100.64.0.0/10)";
        if (isUniqueLocalIpv6(address)) return "unique-local (fc00::/7)";
        return null;
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) return false;
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /** RFC 1122 "this network" block; {@link InetAddress#isAnyLocalAddress()} only covers 0.0.0.0. */
    private static boolean isThisNetworkIpv4(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && bytes[0] == 0;
    }

    /** RFC 6598 carrier-grade NAT / shared address space; also used by Tailscale tailnets. */
    private static boolean isCarrierGradeNatIpv4(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && bytes[0] == 100 && (bytes[1] & 0xC0) == 0x40;
    }

    /**
     * Unwraps an IPv4-mapped ({@code ::ffff:a.b.c.d}), IPv4-compatible ({@code ::a.b.c.d}), or
     * NAT64 well-known-prefix ({@code 64:ff9b::a.b.c.d}, RFC 6052) IPv6 address into the
     * equivalent IPv4 address so it can be reclassified; returns {@code null} when
     * {@code address} is not one of these forms. The JDK already normalizes IPv4-mapped literals
     * to an IPv4 address while parsing text, but this covers addresses that arrive as raw
     * {@link Inet6Address} instances instead of a parsed literal (e.g. a DNS response), and the
     * NAT64 prefix, which the JDK never unwraps on its own. NAT64 matters because an IPv6-only
     * network with a NAT64 gateway resolves {@code 64:ff9b::a00:1} straight through to the
     * private {@code 10.0.0.1}. {@code ::1} and {@code ::} are already handled directly by
     * {@link #classify} before this method is ever consulted, so there is no risk of an
     * all-zero-but-last-byte address being misread as a harmless {@code 0.0.0.x}.
     */
    private static InetAddress unwrapMappedOrCompatible(InetAddress address) {
        if (!(address instanceof Inet6Address)) return null;
        byte[] bytes = address.getAddress();
        byte[] v4;
        if ((bytes[0] & 0xFF) == 0x00 && (bytes[1] & 0xFF) == 0x64 && (bytes[2] & 0xFF) == 0xFF
                && (bytes[3] & 0xFF) == 0x9B && allZero(bytes, 4, 12)) {
            v4 = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
        } else if (allZero(bytes, 0, 10)) {
            boolean mapped = bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
            boolean compatible = bytes[10] == 0 && bytes[11] == 0;
            if (!mapped && !compatible) return null;
            v4 = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
        } else {
            return null;
        }
        try {
            return InetAddress.getByAddress(v4);
        } catch (UnknownHostException impossible) {
            return null;
        }
    }

    private static boolean allZero(byte[] bytes, int fromInclusive, int toExclusive) {
        for (int i = fromInclusive; i < toExclusive; i++) {
            if (bytes[i] != 0) return false;
        }
        return true;
    }
}
