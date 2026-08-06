/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.project;

import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import ai.kompile.cli.main.MainCommand;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParseResult;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the one-command end-to-end wiring on {@code kompile project init}:
 *  - the new --serve/--crawl/--push/--keep-running/--serve-port/--staging-port/--no-staging
 *    options are exposed and parse correctly, and
 *  - the implication contract (push -> crawl -> serve) holds.
 *
 * These are self-contained: they only inspect the picocli model and the pure
 * {@link ProjectServiceCommand#quickstartPlan(boolean, boolean, boolean)} helper, so no
 * services are started and no network calls are made.
 */
class ProjectInitQuickstartTest {

    private static CommandLine initCommand() {
        CommandLine root = new CommandLine(new MainCommand());
        CommandLine project = root.getSubcommands().get("project");
        assertNotNull(project, "project subcommand should be registered");
        CommandLine init = project.getSubcommands().get("init");
        assertNotNull(init, "project init subcommand should be registered");
        return init;
    }

    private static CommandLine startCommand() {
        CommandLine root = new CommandLine(new MainCommand());
        CommandLine project = root.getSubcommands().get("project");
        assertNotNull(project, "project subcommand should be registered");
        CommandLine start = project.getSubcommands().get("start");
        assertNotNull(start, "project start subcommand should be registered");
        return start;
    }

    private static CommandLine serveCommand() {
        CommandLine root = new CommandLine(new MainCommand());
        CommandLine project = root.getSubcommands().get("project");
        assertNotNull(project, "project subcommand should be registered");
        CommandLine serve = project.getSubcommands().get("serve");
        assertNotNull(serve, "project serve subcommand should be registered");
        return serve;
    }

    @Test
    void initExposesQuickstartOptions() {
        CommandSpec spec = initCommand().getCommandSpec();
        for (String opt : new String[]{"--serve", "--crawl", "--push", "--keep-running",
                "--serve-port", "--staging-port", "--no-staging"}) {
            assertNotNull(spec.findOption(opt), "init should expose " + opt);
        }
        assertNull(spec.findOption("--serve-port").defaultValue(),
                "omitted quickstart app port must resolve from Service Endpoints at execution time");
        assertNull(spec.findOption("--staging-port").defaultValue(),
                "omitted quickstart staging port must resolve from Service Endpoints at execution time");
    }

    @Test
    void initParsesQuickstartFlags() {
        // Parse from the root command (parsing a sub-CommandLine in isolation is unsupported),
        // then navigate into the project -> init subcommand parse result. parseArgs does not
        // execute the command, so no services are started.
        CommandLine root = new CommandLine(new MainCommand());
        ParseResult initPr = root.parseArgs("project", "init", "--push", "--serve-port", "9090", "--keep-running")
                .subcommand()   // project
                .subcommand();  // init
        assertTrue(initPr.matchedOptionValue("--push", Boolean.FALSE));
        assertTrue(initPr.matchedOptionValue("--keep-running", Boolean.FALSE));
        assertEquals(9090, (int) initPr.matchedOptionValue("--serve-port", 8080));
    }

    @Test
    void projectStartExposesPersonaOptions() {
        CommandSpec spec = startCommand().getCommandSpec();
        for (String opt : new String[]{"--chat-port", "--crawl-manager-port", "--no-chat",
                "--no-crawl-manager"}) {
            assertNotNull(spec.findOption(opt), "project start should expose " + opt);
        }
    }

    @Test
    void projectStartIncludesBothPersonaServicesByDefault() {
        var defaults = ProjectServiceCommand.Open.personaPorts(null, null, false, false);
        assertTrue(defaults.get(KompileService.CHAT) > 0);
        assertTrue(defaults.get(KompileService.CRAWL) > 0);
        assertFalse(defaults.get(KompileService.CHAT).equals(defaults.get(KompileService.CRAWL)));

        var overridden = ProjectServiceCommand.Open.personaPorts(19081, 19082, false, false);
        assertEquals(19081, overridden.get(KompileService.CHAT));
        assertEquals(19082, overridden.get(KompileService.CRAWL));

        var skipped = ProjectServiceCommand.Open.personaPorts(null, null, true, true);
        assertFalse(skipped.containsKey(KompileService.CHAT));
        assertFalse(skipped.containsKey(KompileService.CRAWL));
    }

    @Test
    void projectLaunchPersistsTheExactProjectEndpointTopology(@TempDir Path tmpDir) throws Exception {
        ServiceEndpointsConfigManager manager = new ServiceEndpointsConfigManager(
                tmpDir.resolve(ServiceEndpointsConfigManager.FILENAME));
        manager.update(Map.of(ServiceEndpointsConfigManager.SERVING_URL_KEY,
                "http://127.0.0.1:19091"));
        Map<KompileService, Integer> personas = Map.of(
                KompileService.CHAT, 18081,
                KompileService.CRAWL, 18082);

        ProjectServiceCommand.Open.persistEndpointTopology(
                manager, 18080, 18090, true, personas);

        Map<String, Object> endpoints = manager.currentAsMap();
        assertEquals("http://localhost:18080", endpoints.get("adminUrl"));
        assertEquals("http://localhost:18081", endpoints.get("chatUrl"));
        assertEquals("http://localhost:18082", endpoints.get("crawlUrl"));
        assertEquals("http://localhost:18090", endpoints.get("stagingUrl"));
        assertEquals("http://127.0.0.1:19091", endpoints.get("servingUrl"));
    }

    @Test
    void projectLaunchPreservesConfiguredEndpointHostsWhenApplyingPorts(@TempDir Path tmpDir)
            throws Exception {
        ServiceEndpointsConfigManager manager = new ServiceEndpointsConfigManager(
                tmpDir.resolve(ServiceEndpointsConfigManager.FILENAME));
        manager.update(Map.of(
                KompileService.ADMIN.configKey(), "http://127.0.0.1:17080",
                KompileService.CHAT.configKey(), "http://127.0.0.1:17081",
                KompileService.CRAWL.configKey(), "http://127.0.0.1:17082",
                ServiceEndpointsConfigManager.STAGING_URL_KEY, "http://127.0.0.1:17090"));

        ProjectServiceCommand.Open.persistEndpointTopology(
                manager, 18080, 18090, true, Map.of(
                        KompileService.CHAT, 18081,
                        KompileService.CRAWL, 18082));

        Map<String, Object> endpoints = manager.currentAsMap();
        assertEquals("http://127.0.0.1:18080", endpoints.get("adminUrl"));
        assertEquals("http://127.0.0.1:18081", endpoints.get("chatUrl"));
        assertEquals("http://127.0.0.1:18082", endpoints.get("crawlUrl"));
        assertEquals("http://127.0.0.1:18090", endpoints.get("stagingUrl"));
    }

    @Test
    void projectLaunchResolvesPersonaPortsFromThatProjectsManagedTopology(@TempDir Path tmpDir)
            throws Exception {
        ServiceEndpointsConfigManager manager =
                ServiceEndpointsConfigManager.forProjectDirectory(tmpDir);
        manager.update(Map.of(
                KompileService.CHAT.configKey(), "http://localhost:19081",
                KompileService.CRAWL.configKey(), "http://localhost:19082"));

        Map<KompileService, Integer> ports = ProjectServiceCommand.Open.personaPorts(
                null, null, false, false, manager);

        assertEquals(19081, ports.get(KompileService.CHAT));
        assertEquals(19082, ports.get(KompileService.CRAWL));
        assertTrue(Files.isRegularFile(tmpDir.resolve("config")
                .resolve(ServiceEndpointsConfigManager.FILENAME)));
    }

    @Test
    void stagingCallbackMutationUsesRequiredRequestHeader() throws Exception {
        AtomicReference<String> requestHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/staging/settings", exchange -> {
            try {
                byte[] response;
                if ("GET".equals(exchange.getRequestMethod())) {
                    response = "{\"callback_timeout_ms\":5000}"
                            .getBytes(StandardCharsets.UTF_8);
                } else {
                    requestHeader.set(exchange.getRequestHeaders()
                            .getFirst("X-Kompile-Staging-Request"));
                    requestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8));
                    response = "{}".getBytes(StandardCharsets.UTF_8);
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            ProjectServiceCommand.Open.configureStagingCallback(
                    server.getAddress().getPort(), 18080);
        } finally {
            server.stop(0);
        }

        assertEquals("1", requestHeader.get());
        assertTrue(requestBody.get().contains(
                "\"callback_url\":\"http://localhost:18080\""));
    }

    @Test
    void projectServeExposesEveryGeneratedLifecycleSelector() {
        CommandSpec spec = serveCommand().getCommandSpec();
        for (String opt : new String[]{"--staging-only", "--serving-only", "--app-only",
                "--chat-only", "--crawl-manager-only", "--workflow", "--dry-run"}) {
            assertNotNull(spec.findOption(opt), "project serve should expose " + opt);
        }
    }

    @Test
    void projectServeRejectsMultipleLifecycleSelectionsBeforeLaunching() {
        CommandLine root = new CommandLine(new MainCommand());
        int exit = root.execute("project", "serve", "--chat-only", "--crawl-manager-only");
        assertEquals(2, exit);
    }

    @Test
    void pushImpliesCrawlImpliesServe() {
        ProjectServiceCommand.QuickstartPlan p = ProjectServiceCommand.quickstartPlan(false, false, true);
        assertTrue(p.serve());
        assertTrue(p.crawl());
        assertTrue(p.push());
    }

    @Test
    void crawlImpliesServeButNotPush() {
        ProjectServiceCommand.QuickstartPlan p = ProjectServiceCommand.quickstartPlan(false, true, false);
        assertTrue(p.serve());
        assertTrue(p.crawl());
        assertFalse(p.push());
    }

    @Test
    void serveAloneDoesNotImplyCrawlOrPush() {
        ProjectServiceCommand.QuickstartPlan p = ProjectServiceCommand.quickstartPlan(true, false, false);
        assertTrue(p.serve());
        assertFalse(p.crawl());
        assertFalse(p.push());
    }

    @Test
    void noFlagsMeansNoQuickstart() {
        ProjectServiceCommand.QuickstartPlan p = ProjectServiceCommand.quickstartPlan(false, false, false);
        assertFalse(p.serve());
        assertFalse(p.crawl());
        assertFalse(p.push());
    }
}
