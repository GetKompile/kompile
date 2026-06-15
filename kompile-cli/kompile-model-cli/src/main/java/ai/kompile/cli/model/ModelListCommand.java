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

package ai.kompile.cli.model;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.http.KompileHttpClient;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "list", description = "List available models.")
public class ModelListCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--local"}, description = "List locally cached models only")
    private boolean local;

    @CommandLine.Option(names = {"--remote"}, description = "List models from remote registry")
    private boolean remote;

    @CommandLine.Option(names = {"--endpoint"}, description = "Staging service URL for remote operations")
    private String endpoint;

    @CommandLine.Option(names = {"--port"}, defaultValue = "8090", description = "Staging service port")
    private int port;

    @Override
    public Integer call() throws Exception {
        if (remote) {
            KompileHttpClient client = KompileHttpClient.create(endpoint, port);
            try {
                String result = client.getString("/api/models");
                System.out.println(result);
            } catch (Exception e) {
                System.err.println("Failed to list remote models: " + e.getMessage());
                return 1;
            }
        } else {
            // Default: list local models
            File modelsDir = KompileHome.modelsDirectory();
            if (!modelsDir.exists() || modelsDir.listFiles() == null) {
                System.out.println("No local models found in " + modelsDir.getAbsolutePath());
                return 0;
            }
            System.out.println("Local models in " + modelsDir.getAbsolutePath() + ":");
            for (File f : modelsDir.listFiles()) {
                if (f.isDirectory()) {
                    System.out.printf("  %-40s %s%n", f.getName(), formatSize(dirSize(f)));
                } else {
                    System.out.printf("  %-40s %s%n", f.getName(), formatSize(f.length()));
                }
            }
        }
        return 0;
    }

    private long dirSize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                size += f.isDirectory() ? dirSize(f) : f.length();
            }
        }
        return size;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
