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

package ai.kompile.cli.app;

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;
import picocli.CommandLine;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@CommandLine.Command(name = "stop", description = "Gracefully stop a running Kompile application.")
public class AppStopCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--url"}, description = "Application URL (overrides auto-discovery)")
    private String url;

    @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "8080", description = "Port of the application")
    private int port;

    @CommandLine.Option(names = {"--name"}, defaultValue = "default", description = "Instance name")
    private String name;

    @Override
    public Integer call() throws Exception {
        InstanceInfo info = null;
        String targetUrl = url;
        if (targetUrl == null || targetUrl.isBlank()) {
            info = InstanceRegistry.get(name);
            if (info == null) {
                info = InstanceRegistry.findByPort(port);
            }
            if (info != null) {
                targetUrl = info.getUrl();
            } else {
                targetUrl = "http://localhost:" + port;
            }
        }

        KompileHttpClient client = new KompileHttpClient(targetUrl);
        try {
            client.postEmpty("/actuator/shutdown");
            System.out.println("Shutdown signal sent to " + targetUrl);
            InstanceRegistry.unregister(info != null ? info.getName() : name);
            return 0;
        } catch (Exception e) {
            return stopRegisteredProcess(targetUrl, info, e);
        }
    }

    private Integer stopRegisteredProcess(String targetUrl, InstanceInfo info, Exception shutdownFailure) {
        if (info == null) {
            System.err.println("Failed to stop application at " + targetUrl + ": " + shutdownFailure.getMessage());
            System.err.println("No registered PID found for --name '" + name + "' or port " + port + ".");
            return 1;
        }

        Optional<ProcessHandle> process = ProcessHandle.of(info.getPid());
        if (process.isEmpty() || !process.get().isAlive()) {
            InstanceRegistry.unregister(info.getName());
            System.out.println("Application process was already stopped; removed stale registry entry for " + info.getName());
            return 0;
        }

        System.out.println("Actuator shutdown unavailable at " + targetUrl + ": " + shutdownFailure.getMessage());
        System.out.println("Stopping registered process " + info.getPid() + " for instance '" + info.getName() + "'...");
        ProcessHandle handle = process.get();
        handle.destroy();
        try {
            handle.onExit().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.out.println("Graceful stop timed out; force killing PID " + info.getPid());
            handle.destroyForcibly();
            try {
                handle.onExit().get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // ProcessHandle.destroyForcibly is best-effort on some platforms.
            }
        }
        InstanceRegistry.unregister(info.getName());
        System.out.println("Stopped application '" + info.getName() + "'");
        return 0;
    }
}
