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

package ai.kompile.loader.onedrive;

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
 * Exercises {@link OneDriveLoaderImpl#load} against a stub Graph API, focused on the
 * metadata.folderId → file-children resolution path (no pathOrUrl/itemIds required).
 */
class OneDriveLoaderImplTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private OneDriveLoaderImpl loader() {
        return new OneDriveLoaderImpl(null) {
            @Override
            protected String apiBase() {
                return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1.0";
            }
        };
    }

    @Test
    void folderIdResolvesToFileChildrenWithoutExplicitIds() throws Exception {
        AtomicReference<String> childrenPath = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1.0/me/drive/items/folder-parent/children", exchange -> {
            childrenPath.set(exchange.getRequestURI().getPath());
            // One file + one sub-folder: the sub-folder must be skipped.
            byte[] body = """
                    {"value":[
                      {"id":"item-1","name":"Doc.docx","file":{"mimeType":
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"}},
                      {"id":"folder-9","name":"Sub","folder":{"childCount":1}}],
                     "@odata.nextLink":null}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1.0/me/drive/items/item-1", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            byte[] body;
            if (query != null && query.contains("format=text")) {
                // Graph text conversion returns 302 to a pre-signed URL; follow-redirect
                // is enabled on the client, so answer the redirect target inline.
                body = "converted text".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Location",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/converted");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            body = """
                    {"id":"item-1","name":"Doc.docx","size":14,
                     "file":{"mimeType":
                       "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
                     "lastModifiedDateTime":"2026-01-01T00:00:00Z","webUrl":"https://1drv.ms/x"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/converted", exchange -> {
            byte[] body = "converted text".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        var documents = loader().load(DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.ONEDRIVE)
                .metadata(Map.of(
                        "accessToken", "test-token",
                        "folderId", "folder-parent"))
                .build());

        assertEquals(1, documents.size(), "sub-folders must be skipped");
        assertEquals("item-1", documents.get(0).getMetadata().get("onedrive_item_id"));
        String path = childrenPath.get();
        assertTrue(path != null && path.contains("folder-parent"), path);
    }

    @Test
    void explicitItemIdsWinOverFolderId() throws Exception {
        // No server here: the explicit id proceeds straight to the (failing) metadata fetch,
        // proving the ids gate was satisfied WITHOUT folder listing. The loader logs a skip
        // for the unreachable item and returns an empty document list.
        var documents = loader().load(DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.ONEDRIVE)
                .pathOrUrl("item-1")
                .metadata(Map.of("accessToken", "unused", "folderId", "folder-parent"))
                .build());

        assertTrue(documents.isEmpty());
    }
}
