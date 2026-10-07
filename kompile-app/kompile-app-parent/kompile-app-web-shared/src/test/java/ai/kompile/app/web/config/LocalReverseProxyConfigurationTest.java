/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package ai.kompile.app.web.config;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalReverseProxyConfigurationTest {

    /** Echoes what application code sees after the engine valves ran. */
    private static final class EchoServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.getWriter().write(request.isSecure() + "|" + request.getScheme() + "|"
                    + request.getServerName() + "|" + request.getServerPort() + "|" + request.getRemoteAddr());
        }
    }

    private static String echo(WebServer server, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.getPort() + "/echo")).GET();
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void appliesTailscaleServeHeadersFromLoopbackPeer() throws Exception {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
        new LocalReverseProxyConfiguration().localReverseProxyCustomizer().customize(factory);
        WebServer server = factory.getWebServer(context ->
                context.addServlet("echo", new EchoServlet()).addMapping("/echo"));
        server.start();
        try {
            // Headers tailscale serve sends (ipn/ipnlocal/serve.go addProxyForwardedHeaders).
            assertEquals("true|https|kompile-box.tail1234.ts.net|443|100.101.102.103", echo(server,
                    "X-Forwarded-Proto", "https",
                    "X-Forwarded-Host", "kompile-box.tail1234.ts.net",
                    "X-Forwarded-For", "100.101.102.103"));
            // Unproxied loopback traffic (CLI, local browser) is untouched.
            assertEquals("false|http|127.0.0.1|" + server.getPort() + "|127.0.0.1", echo(server));
        } finally {
            server.stop();
        }
    }

    @Test
    void trustsOnlyLoopbackPeersAsProxies() {
        Pattern proxies = Pattern.compile(LocalReverseProxyConfiguration.LOOPBACK_PROXIES);
        assertEquals(LocalReverseProxyConfiguration.LOOPBACK_PROXIES,
                LocalReverseProxyConfiguration.localReverseProxyValve().getInternalProxies());
        assertTrue(proxies.matcher("127.0.0.1").matches());
        assertTrue(proxies.matcher("0:0:0:0:0:0:0:1").matches());
        for (String peer : new String[]{"100.101.102.103", "192.168.1.20", "10.0.0.5", "172.16.0.1", "fd7a::1"}) {
            assertFalse(proxies.matcher(peer).matches(), peer);
        }
    }
}
