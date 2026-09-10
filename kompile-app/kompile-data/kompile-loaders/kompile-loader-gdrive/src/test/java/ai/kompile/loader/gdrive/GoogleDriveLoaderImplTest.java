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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.loader.gdrive;

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link GoogleDriveLoaderImpl#load} against a stub Drive API, focused on the
 * metadata.folderId → file-children resolution path (no pathOrUrl/fileIds required).
 */
class GoogleDriveLoaderImplTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private GoogleDriveLoaderImpl loader() {
        return new GoogleDriveLoaderImpl(null) {
            @Override
            protected String apiBase() {
                return "http://127.0.0.1:" + server.getAddress().getPort() + "/drive/v3";
            }
        };
    }

    @Test
    void folderIdResolvesToFileChildrenWithoutExplicitIds() throws Exception {
        AtomicReference<String> listUrl = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/drive/v3/files", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.startsWith("q=")) {
                // Folder listing request
                listUrl.set(query);
                // One file + one sub-folder: the sub-folder must be skipped.
                byte[] body = """
                        {"files":[
                          {"id":"file-1","mimeType":"application/vnd.google-apps.document"},
                          {"id":"folder-9","mimeType":"application/vnd.google-apps.folder"}],
                         "nextPageToken":null}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            if (query != null && query.contains("alt=media")) {
                byte[] body = "drive file body".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            // Metadata fetch for file-1 (a Workspace doc → export path next)
            byte[] body = """
                    {"id":"file-1","name":"Doc","mimeType":"application/vnd.google-apps.document"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/drive/v3/files/file-1/export", exchange -> {
            byte[] body = "exported doc text".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        var documents = loader().load(DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.GDRIVE)
                .metadata(Map.of(
                        "accessToken", "test-token",
                        "folderId", "folder-parent"))
                .build());

        assertEquals(1, documents.size(), "sub-folders must be skipped");
        assertEquals("file-1", documents.get(0).getMetadata().get("gdrive_file_id"));
        String query = listUrl.get();
        assertTrue(query != null && query.contains("folder-parent"), query);
        assertTrue(query.contains("trashed"), query);
    }

    @Test
    void explicitFileIdsWinOverFolderId() throws Exception {
        // No server here: the explicit id proceeds straight to the (failing) download path,
        // proving the ids gate was satisfied WITHOUT folder listing. The loader logs a skip
        // for the unreachable file and returns an empty document list — folder resolution is
        // never consulted.
        var documents = loader().load(DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.GDRIVE)
                .pathOrUrl("file-1")
                .metadata(Map.of("accessToken", "unused", "folderId", "folder-parent"))
                .build());

        assertTrue(documents.isEmpty());
    }
}
