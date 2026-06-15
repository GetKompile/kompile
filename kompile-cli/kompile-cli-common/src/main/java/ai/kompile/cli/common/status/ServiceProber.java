/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.common.status;

import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Probes known and registered Kompile services for health status.
 */
public class ServiceProber {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

    private static final List<ServiceDefinition> KNOWN_SERVICES = List.of(
            new ServiceDefinition("kompile-app", 8080, "/actuator/health"),
            new ServiceDefinition("kompile-app", 8081, "/actuator/health"),
            new ServiceDefinition("kompile-model-staging", 8090, "/actuator/health"),
            new ServiceDefinition("pipeline-serve", 9090, "/health"),
            new ServiceDefinition("pipeline-serve", 9091, "/health")
    );

    /**
     * Probes all known services and registered instances concurrently.
     */
    public static List<ServiceStatus> probeAll() {
        Map<Integer, ServiceDefinition> toProbe = new LinkedHashMap<>();

        // Add known services
        for (ServiceDefinition def : KNOWN_SERVICES) {
            toProbe.put(def.port, def);
        }

        // Add registered instances (dedup by port)
        try {
            for (InstanceInfo info : InstanceRegistry.listAll()) {
                if (!toProbe.containsKey(info.getPort())) {
                    String healthPath = "app".equals(info.getType()) || "staging".equals(info.getType())
                            ? "/actuator/health" : "/health";
                    toProbe.put(info.getPort(), new ServiceDefinition(
                            info.getName(), info.getPort(), healthPath));
                }
            }
        } catch (Exception e) {
            // Registry unavailable, continue with known services
        }

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(toProbe.size(), 8));
        try {
            List<CompletableFuture<ServiceStatus>> futures = new ArrayList<>();
            for (ServiceDefinition def : toProbe.values()) {
                futures.add(CompletableFuture.supplyAsync(() -> probeOne(def), executor));
            }

            List<ServiceStatus> results = new ArrayList<>();
            for (CompletableFuture<ServiceStatus> f : futures) {
                try {
                    results.add(f.get(3, TimeUnit.SECONDS));
                } catch (Exception e) {
                    // Should not happen since probeOne catches internally
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Probes a single service definition.
     */
    public static ServiceStatus probeOne(ServiceDefinition def) {
        String url = "http://localhost:" + def.port + def.healthPath;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            boolean healthy = response.statusCode() >= 200 && response.statusCode() < 300;
            return new ServiceStatus(def.name, url, def.port, healthy, elapsed);
        } catch (Exception e) {
            return new ServiceStatus(def.name, url, def.port, false, -1);
        }
    }

    /**
     * A known service endpoint to probe.
     */
    public static class ServiceDefinition {
        private String name;
        private int port;
        private String healthPath;

        public ServiceDefinition() {}

        public ServiceDefinition(String name, int port, String healthPath) {
            this.name = name;
            this.port = port;
            this.healthPath = healthPath;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getHealthPath() { return healthPath; }
        public void setHealthPath(String healthPath) { this.healthPath = healthPath; }
    }

    /**
     * Result of probing a service.
     */
    public static class ServiceStatus {
        private String name;
        private String url;
        private int port;
        private boolean healthy;
        private long responseTimeMs;

        public ServiceStatus() {}

        public ServiceStatus(String name, String url, int port, boolean healthy, long responseTimeMs) {
            this.name = name;
            this.url = url;
            this.port = port;
            this.healthy = healthy;
            this.responseTimeMs = responseTimeMs;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public boolean isHealthy() { return healthy; }
        public void setHealthy(boolean healthy) { this.healthy = healthy; }
        public long getResponseTimeMs() { return responseTimeMs; }
        public void setResponseTimeMs(long responseTimeMs) { this.responseTimeMs = responseTimeMs; }
    }
}
