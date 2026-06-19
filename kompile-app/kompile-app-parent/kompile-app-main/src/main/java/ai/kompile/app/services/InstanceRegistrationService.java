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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
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
    private volatile boolean registered = false;

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

            InstanceInfo info = new InstanceInfo(INSTANCE_NAME, INSTANCE_TYPE, port, pid, null, null, Instant.now());
            InstanceRegistry.register(info);
            registered = true;

            log.info("Registered app instance in ~/.kompile/instances/ (port={}, pid={})", port, pid);
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
            InstanceRegistry.unregister(INSTANCE_NAME);
            log.info("Unregistered app instance from ~/.kompile/instances/");
        } catch (Exception e) {
            log.warn("Failed to unregister app instance: {}", e.getMessage());
        }
    }
}
