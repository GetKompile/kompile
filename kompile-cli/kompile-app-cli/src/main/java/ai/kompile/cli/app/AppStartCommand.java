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

import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;
import picocli.CommandLine;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "start", description = "Launch a Kompile application subprocess.")
public class AppStartCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "8080", description = "Port to run on (default: 8080)")
    private int port;

    @CommandLine.Option(names = {"--jar"}, description = "Path to the application JAR file")
    private String jarPath;

    @CommandLine.Option(names = {"--config"}, description = "Path to application.properties")
    private String configPath;

    @CommandLine.Option(names = {"--name"}, defaultValue = "default", description = "Instance name")
    private String name;

    @Override
    public Integer call() throws Exception {
        if (jarPath == null || jarPath.isBlank()) {
            System.err.println("Error: --jar is required. Specify the path to kompile-app-main JAR.");
            return 1;
        }

        File jar = new File(jarPath);
        if (!jar.exists()) {
            System.err.println("Error: JAR not found: " + jarPath);
            return 1;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-jar");
        cmd.add(jar.getAbsolutePath());
        cmd.add("--server.port=" + port);
        if (configPath != null) {
            cmd.add("--spring.config.additional-location=" + configPath);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        Process process = pb.start();

        long pid = process.pid();
        InstanceRegistry.register(InstanceInfo.builder()
                .name(name).type("app").port(port).pid(pid).jarPath(jar.getAbsolutePath())
                .startedAt(java.time.Instant.now()).build());

        System.out.println("Started Kompile app '" + name + "' on port " + port + " (PID: " + pid + ")");
        return 0;
    }
}
