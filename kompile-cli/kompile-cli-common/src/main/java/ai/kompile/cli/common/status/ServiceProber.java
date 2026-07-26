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
import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.common.routing.KompileServiceEndpoints;

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

    private static final List<ServiceDefinition> KNOWN_AUXILIARY_SERVICES = List.of(
            new ServiceDefinition("kompile-model-staging", 8090, "/actuator/health"),
            new ServiceDefinition("pipeline-serve", 9090, "/health"),
            new ServiceDefinition("pipeline-serve", 9091, "/health")
    );

    /**
     * The three persona apps, each at its resolved port.
     *
     * <p>Built per call rather than held in a constant so a port override is reflected, and named by
     * component id so status output distinguishes the admin console from chat and the crawl manager —
     * the old list had two entries both labelled {@code kompile-app} and no :8082 at all.</p>
     */
    private static List<ServiceDefinition> personaServices() {
        List<ServiceDefinition> defs = new ArrayList<>();
        for (KompileService service : KompileService.values()) {
            defs.add(new ServiceDefinition(service.componentId(),
                    KompileServiceEndpoints.resolve(service).port(), "/actuator/health"));
        }
        return defs;
    }

    /**
     * Probes all known services and registered instances concurrently.
     */
    public static List<ServiceStatus> probeAll() {
        Map<Integer, ServiceDefinition> toProbe = new LinkedHashMap<>();

        // Add known services
        for (ServiceDefinition def : personaServices()) {
            toProbe.put(def.getPort(), def);
        }
        for (ServiceDefinition def : KNOWN_AUXILIARY_SERVICES) {
            toProbe.putIfAbsent(def.getPort(), def);
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
        String url = "http://localhost:" + def.getPort() + def.getHealthPath();
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
            return new ServiceStatus(def.getName(), url, def.getPort(), healthy, elapsed);
        } catch (Exception e) {
            return new ServiceStatus(def.getName(), url, def.getPort(), false, -1);
        }
    }
}
