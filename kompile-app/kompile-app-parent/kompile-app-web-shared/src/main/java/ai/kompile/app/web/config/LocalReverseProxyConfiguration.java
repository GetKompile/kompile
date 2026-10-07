/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package ai.kompile.app.web.config;

import org.apache.catalina.valves.RemoteIpValve;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lets every persona sit behind a TLS-terminating reverse proxy that runs on the same host
 * ({@code tailscale serve}, a local Caddy/nginx) and still see the request the browser made.
 *
 * <p>Such a proxy connects from loopback and forwards {@code X-Forwarded-Proto/-For/-Host/-Port}.
 * Without honouring them every proxied request looks like plain HTTP from 127.0.0.1, so the
 * browser's {@code https://} origin fails the CORS same-origin check and the HTTPS-only gates
 * (integrations, source mutations, host terminal) reject a connection that really is HTTPS.</p>
 *
 * <p>Forwarded headers are trusted ONLY from loopback peers. Any process that can reach loopback
 * is already trusted as local by those gates, so this grants no authority a remote client lacks;
 * the same headers from any other peer are ignored by the valve.</p>
 */
@Configuration(proxyBeanMethods = false)
public class LocalReverseProxyConfiguration {

    static final String LOOPBACK_PROXIES =
            "127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|0:0:0:0:0:0:0:1|::1";

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> localReverseProxyCustomizer() {
        return factory -> factory.addEngineValves(localReverseProxyValve());
    }

    static RemoteIpValve localReverseProxyValve() {
        RemoteIpValve valve = new RemoteIpValve();
        valve.setInternalProxies(LOOPBACK_PROXIES);
        valve.setRemoteIpHeader("X-Forwarded-For");
        valve.setProtocolHeader("X-Forwarded-Proto");
        valve.setHostHeader("X-Forwarded-Host");
        valve.setPortHeader("X-Forwarded-Port");
        return valve;
    }
}
