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

package ai.kompile.app.services;

import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Registers the running kompile-app instance in {@code ~/.kompile/instances/}
 * so that CLI tools can discover and connect to it automatically.
 *
 * <p>On {@link ApplicationReadyEvent}, writes a JSON descriptor with the actual
 * bound port and PID. On shutdown, removes the descriptor so stale entries
 * don't accumulate.</p>
 */
@Service
public class InstanceRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(InstanceRegistrationService.class);

    private static final String INSTANCE_NAME = "default";
    private static final String INSTANCE_TYPE = "app";

    private final ServerPortService serverPortService;

    @Value("${kompile.project.root:}")
    private String configuredProjectRoot;

    private volatile boolean registered = false;
    private volatile String registeredInstanceName;

    @Autowired
    public InstanceRegistrationService(ServerPortService serverPortService) {
        this.serverPortService = serverPortService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1) // After staging auto-start (Order 0)
    public void onApplicationReady() {
        try {
            int port = serverPortService.getActualPort();
            long pid = ProcessHandle.current().pid();
            Path projectRoot = resolveProjectRoot(configuredProjectRoot);
            String instanceName = projectRoot == null
                    ? INSTANCE_NAME
                    : "app-" + sanitize(projectRoot.getFileName().toString()) + "-" + port;

            InstanceInfo info = new InstanceInfo(instanceName, INSTANCE_TYPE, port, pid, null,
                    projectRoot == null ? null : projectRoot.toString(), Instant.now());
            InstanceRegistry.register(info);
            registeredInstanceName = instanceName;
            registered = true;

            log.info("Registered app instance in ~/.kompile/instances/ (name={}, project={}, port={}, pid={})",
                    instanceName, projectRoot, port, pid);
        } catch (Exception e) {
            log.warn("Failed to register app instance: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void onShutdown() {
        if (!registered) {
            return;
        }
        try {
            InstanceRegistry.unregister(registeredInstanceName);
            log.info("Unregistered app instance {} from ~/.kompile/instances/", registeredInstanceName);
        } catch (Exception e) {
            log.warn("Failed to unregister app instance: {}", e.getMessage());
        }
    }

    static Path resolveProjectRoot(String configuredRoot) {
        String value = configuredRoot;
        if (value == null || value.isBlank()) {
            value = System.getenv("KOMPILE_PROJECT_ROOT");
        }
        if (value != null && !value.isBlank()) {
            return Path.of(value).toAbsolutePath().normalize();
        }
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return Files.isRegularFile(workingDirectory.resolve("kompile.project.json"))
                ? workingDirectory : null;
    }

    static String sanitize(String value) {
        String sanitized = value.replaceAll("[^a-zA-Z0-9._-]", "-");
        return sanitized.isBlank() ? "project" : sanitized;
    }
}
