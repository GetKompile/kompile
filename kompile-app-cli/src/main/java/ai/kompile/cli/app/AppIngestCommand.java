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
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "ingest", description = "Ingest documents into the RAG application.")
public class AppIngestCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--path", "--file", "-f"}, description = "File or directory to ingest", required = true)
    private Path filePath;

    @CommandLine.Option(names = {"--loader"}, description = "Loader to use (e.g., pdf-extended, tika). Auto-detected if omitted.")
    private String loader;

    @CommandLine.Option(names = {"--chunker"}, description = "Chunker to use (e.g., recursive, sentence). Uses default if omitted.")
    private String chunkerName;

    @CommandLine.Option(names = {"--url"}, description = "Application URL")
    private String url;

    @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "8080", description = "Port of the application")
    private int port;

    @Override
    public Integer call() throws Exception {
        Path resolved = filePath.toAbsolutePath().normalize();
        if (!Files.exists(resolved)) {
            System.err.println("Path does not exist: " + resolved);
            return 1;
        }

        KompileHttpClient client = KompileHttpClient.create(url, port);

        if (Files.isDirectory(resolved)) {
            return ingestDirectory(client, resolved);
        } else {
            return ingestSinglePath(client, resolved);
        }
    }

    private int ingestSinglePath(KompileHttpClient client, Path path) {
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("path", path.toString());
            if (loader != null) body.put("loader", loader);
            if (chunkerName != null) body.put("chunkerName", chunkerName);

            String result = client.postString("/api/documents/add-path", body);
            System.out.println("Ingestion started: " + result);
            return 0;
        } catch (Exception e) {
            System.err.println("Ingestion failed: " + e.getMessage());
            return 1;
        }
    }

    private int ingestDirectory(KompileHttpClient client, Path dir) {
        System.out.println("Ingesting directory: " + dir);
        int[] counts = {0, 0}; // [success, fail]
        try {
            Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .filter(p -> isSupportedFile(p.getFileName().toString()))
                    .forEach(p -> {
                        int rc = ingestSinglePath(client, p);
                        if (rc == 0) counts[0]++;
                        else counts[1]++;
                    });
        } catch (IOException e) {
            System.err.println("Failed to walk directory: " + e.getMessage());
            return 1;
        }
        System.out.println("Ingestion complete: " + counts[0] + " succeeded, " + counts[1] + " failed");
        return counts[1] > 0 ? 1 : 0;
    }

    private static boolean isSupportedFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".txt") || lower.endsWith(".md")
                || lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xml")
                || lower.endsWith(".json") || lower.endsWith(".jsonl")
                || lower.endsWith(".csv") || lower.endsWith(".tsv")
                || lower.endsWith(".yaml") || lower.endsWith(".yml")
                || lower.endsWith(".doc") || lower.endsWith(".docx")
                || lower.endsWith(".xls") || lower.endsWith(".xlsx")
                || lower.endsWith(".ppt") || lower.endsWith(".pptx")
                || lower.endsWith(".odt") || lower.endsWith(".ods") || lower.endsWith(".odp")
                || lower.endsWith(".rtf") || lower.endsWith(".epub");
    }
}
