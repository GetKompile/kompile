package ai.kompile.staging.http;

import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicAddressDnsResolverTest {

    @Test
    void acceptsOnlyGloballyRoutableIpv4AndIpv6Addresses() throws Exception {
        InetAddress[] addresses = addresses(
                "8.8.8.8",
                "1.1.1.1",
                "2001:4860:4860::8888",
                "2606:4700:4700::1111");
        PublicAddressDnsResolver resolver =
                new PublicAddressDnsResolver(fixed(addresses), false);

        InetAddress[] resolved = resolver.resolve("public.example");

        assertArrayEquals(addresses, resolved);
        assertNotSame(addresses, resolved);
    }

    @Test
    void rejectsEveryPrivateReservedAndDocumentationIpv4Range() {
        for (String address : List.of(
                "0.0.0.1",
                "10.0.0.1",
                "100.64.0.1",
                "127.0.0.1",
                "169.254.169.254",
                "172.16.0.1",
                "192.0.0.1",
                "192.0.2.1",
                "192.88.99.1",
                "192.168.1.1",
                "198.18.0.1",
                "198.51.100.1",
                "203.0.113.1",
                "224.0.0.1",
                "240.0.0.1",
                "255.255.255.255")) {
            PublicAddressDnsResolver resolver =
                    new PublicAddressDnsResolver(fixed(address), false);
            assertThrows(
                    UnknownHostException.class,
                    () -> resolver.resolve("blocked.example"),
                    address);
        }
    }

    @Test
    void rejectsNonGlobalAndTransitionIpv6Ranges() {
        for (String address : List.of(
                "::",
                "::1",
                "fe80::1",
                "fc00::1",
                "2001::1",
                "2001:db8::1",
                "2002:c0a8:1::1",
                "3fff::1",
                "ff02::1")) {
            PublicAddressDnsResolver resolver =
                    new PublicAddressDnsResolver(fixed(address), false);
            assertThrows(
                    UnknownHostException.class,
                    () -> resolver.resolve("blocked-v6.example"),
                    address);
        }
    }

    @Test
    void rejectsMixedPublicAndPrivateDnsAnswers() throws Exception {
        PublicAddressDnsResolver resolver = new PublicAddressDnsResolver(
                fixed(addresses("8.8.8.8", "127.0.0.1")), false);

        assertThrows(
                UnknownHostException.class,
                () -> resolver.resolve("rebinding.example"));
    }

    @Test
    void canonicalLookupCannotBypassAddressValidation() {
        PublicAddressDnsResolver resolver =
                new PublicAddressDnsResolver(fixed("169.254.169.254"), false);

        assertThrows(
                UnknownHostException.class,
                () -> resolver.resolveCanonicalHostname("metadata.example"));
    }

    @Test
    void loopbackEscapeHatchIsExplicitAndTestOnly() throws Exception {
        PublicAddressDnsResolver resolver =
                new PublicAddressDnsResolver(fixed("127.0.0.1"), true);

        assertEquals(
                "127.0.0.1",
                resolver.resolve("local.test")[0].getHostAddress());
    }

    private static DnsResolver fixed(String address) {
        try {
            return fixed(addresses(address));
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static DnsResolver fixed(InetAddress[] addresses) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) {
                return addresses;
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    private static InetAddress[] addresses(String... values)
            throws UnknownHostException {
        InetAddress[] result = new InetAddress[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = InetAddress.getByName(values[i]);
        }
        return result;
    }
}
