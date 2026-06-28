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

package ai.kompile.app.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seeds the irreducible Spring-framework bootstrap defaults that the kompile application and every
 * generated RAG instance need to start. This is what lets a generated project ship with NO
 * {@code application.properties}: all kompile <em>feature</em> configuration lives in the managed
 * JSON config ({@code <dataDir>/config/*.json}) read by the {@code *ConfigService} beans, and the
 * only thing left — framework plumbing (datasource, web server, multipart, the MCP server, the
 * per-project data root) — is supplied here from the dependency jar.
 *
 * <p>Registered via app-main's {@code META-INF/spring.factories} under
 * {@code org.springframework.boot.env.EnvironmentPostProcessor}. It lives in <strong>app-main</strong>
 * (not app-core) on purpose: only the full web application and generated instances depend on
 * app-main, so the datasource / MCP-server defaults do NOT leak into the lightweight subprocesses
 * (embedding, vector, etc.) that depend on app-core alone.
 *
 * <p><strong>Precedence:</strong> added with {@code addLast} (lowest precedence) so anything explicit
 * overrides it — a {@code -D} system property, an OS environment variable, a managed JSON value
 * bridged into the {@code Environment}, or any {@code application.properties} a caller supplies.
 *
 * <p><strong>Per-project home dir:</strong> {@code kompile.data.dir} is seeded here (honouring an
 * explicit {@code -Dkompile.data.dir=<projectDir>}, else the global {@code ~/.kompile}); the H2
 * datasource and on-disk state are expressed relative to it so they follow the active project.
 */
public class KompileBootstrapEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String PROPERTY_SOURCE_NAME = "kompile-bootstrap-defaults";

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new LinkedHashMap<>();

        // --- Per-project data root: honour -Dkompile.data.dir, else the global home. ---
        String dataDir = environment.getProperty("kompile.data.dir");
        if (dataDir == null || dataDir.isBlank()) {
            defaults.put("kompile.data.dir", System.getProperty("user.home") + File.separator + ".kompile");
        }

        // --- Identity ---
        defaults.put("spring.application.name", "kompile-app");

        // --- Orchestrator JPA datasource (H2, file-backed, per-project under the data dir). ---
        defaults.put("spring.datasource.url",
                "jdbc:h2:file:${kompile.data.dir}/data/orchestrator-db;DB_CLOSE_ON_EXIT=FALSE;DB_CLOSE_DELAY=-1;AUTO_RECONNECT=TRUE");
        defaults.put("spring.datasource.driverClassName", "org.h2.Driver");
        defaults.put("spring.datasource.username", "sa");
        defaults.put("spring.datasource.password", "");
        defaults.put("spring.jpa.database-platform", "org.hibernate.dialect.H2Dialect");
        defaults.put("spring.jpa.hibernate.ddl-auto", "update");
        // ByteBuddy is excluded from the classpath for native-image compat; Hibernate falls back to 'none'.
        defaults.put("spring.jpa.properties.hibernate.bytecode.provider", "none");

        // --- H2 console (dev aid; loopback only). ---
        defaults.put("spring.h2.console.enabled", "true");
        defaults.put("spring.h2.console.path", "/h2-console");
        defaults.put("spring.h2.console.settings.web-allow-others", "false");

        // --- MCP server (the app exposes its tools over MCP/SSE). ---
        defaults.put("spring.ai.mcp.server.enabled", "true");
        defaults.put("spring.ai.mcp.server.name", "kompile-mcp-server");
        defaults.put("spring.ai.mcp.server.version", "1.0.0");
        defaults.put("spring.ai.mcp.server.type", "SYNC");
        defaults.put("spring.ai.mcp.server.sse-endpoint", "/sse");
        defaults.put("spring.ai.mcp.server.sse-message-endpoint", "/mcp/message");

        // --- Multi-module assembly: allow same-named beans across assembled modules to override. ---
        defaults.put("spring.main.allow-bean-definition-overriding", "true");

        // --- Web server: graceful shutdown, large uploads, long-lived streaming/SSE. ---
        defaults.put("server.shutdown", "graceful");
        defaults.put("spring.lifecycle.timeout-per-shutdown-phase", "10s");
        defaults.put("server.tomcat.max-http-response-header-size", "100MB");
        defaults.put("server.tomcat.max-http-post-size", "100MB");
        defaults.put("server.tomcat.max-swallow-size", "100MB");
        defaults.put("server.tomcat.connection-timeout", "600000");
        defaults.put("server.tomcat.keep-alive-timeout", "120000");
        defaults.put("server.tomcat.max-http-form-post-size", "-1");
        defaults.put("server.tomcat.threads.max", "200");
        defaults.put("server.tomcat.threads.min-spare", "10");
        defaults.put("server.tomcat.accept-count", "100");
        defaults.put("spring.servlet.multipart.enabled", "true");
        defaults.put("spring.servlet.multipart.max-file-size", "500MB");
        defaults.put("spring.servlet.multipart.max-request-size", "500MB");
        defaults.put("spring.servlet.multipart.file-size-threshold", "10MB");
        defaults.put("spring.mvc.async.request-timeout", "1800000");
        defaults.put("server.servlet.session.timeout", "30m");

        // --- Jackson: tolerate empty beans, ISO-8601 dates. ---
        defaults.put("spring.jackson.serialization.fail-on-empty-beans", "false");
        defaults.put("spring.jackson.serialization.write-dates-as-timestamps", "false");

        // --- Baseline logging (kompile at INFO; finer levels are a logback concern). ---
        defaults.put("logging.level.ai.kompile", "INFO");

        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }
}
