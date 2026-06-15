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

import ai.kompile.cli.common.http.KompileHttpClient;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "archive", description = "Manage .karch model archives via the staging service.")
public class ModelArchiveCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Operation: export, import, list, update")
    private String operation;

    @CommandLine.Option(names = {"--path"}, description = "Archive path (for export/import)")
    private String path;

    @CommandLine.Option(names = {"--model", "-m"}, description = "Model ID (for export/update)")
    private String modelId;

    @CommandLine.Option(names = {"--endpoint"}, description = "Staging service URL")
    private String endpoint;

    @CommandLine.Option(names = {"--port"}, defaultValue = "8090", description = "Staging service port")
    private int port;

    @Override
    public Integer call() throws Exception {
        KompileHttpClient client = KompileHttpClient.create(endpoint, port);
        try {
            switch (operation) {
                case "list":
                    System.out.println(client.getString("/api/archives"));
                    break;

                case "export":
                    if (modelId == null || modelId.isBlank()) {
                        System.err.println("--model is required for archive export.");
                        return 1;
                    }
                    if (path == null || path.isBlank()) {
                        System.err.println("--path is required for archive export.");
                        return 1;
                    }
                    String exportResult = client.postString("/api/archives/export",
                            Map.of("modelId", modelId, "outputPath", path));
                    System.out.println("Archive exported: " + exportResult);
                    break;

                case "import":
                    if (path == null || path.isBlank()) {
                        System.err.println("--path is required for archive import.");
                        return 1;
                    }
                    Path archivePath = Path.of(path);
                    if (!Files.exists(archivePath)) {
                        System.err.println("Archive file not found: " + path);
                        return 1;
                    }
                    String importResult = client.uploadFile("/api/archives/import", archivePath);
                    System.out.println("Archive imported: " + importResult);
                    break;

                case "update":
                    if (modelId == null || modelId.isBlank()) {
                        System.err.println("--model is required for archive update.");
                        return 1;
                    }
                    String updateResult = client.postString("/api/archives/update",
                            Map.of("modelId", modelId));
                    System.out.println("Archive updated: " + updateResult);
                    break;

                default:
                    System.err.println("Unknown operation: " + operation);
                    System.err.println("Valid operations: list, export, import, update");
                    return 1;
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Archive operation failed: " + e.getMessage());
            return 1;
        }
    }
}
