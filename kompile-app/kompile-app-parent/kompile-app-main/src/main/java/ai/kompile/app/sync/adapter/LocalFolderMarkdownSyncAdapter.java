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

package ai.kompile.app.sync.adapter;

import ai.kompile.app.facts.domain.Note;
import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.app.sync.dto.SyncConnectionTestResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Sync adapter for local Markdown folders.
 */
@Service
public class LocalFolderMarkdownSyncAdapter implements SyncAdapter {

    @Autowired
    private LocalMarkdownFileStore fileStore;

    @Override
    public String adapterId() {
        return "local_folder";
    }

    @Override
    public List<ExternalNoteSnapshot> fetchChangedSince(NoteSyncConnection conn, Instant since) {
        return fileStore.fetchChangedSince(conn, since);
    }

    @Override
    public Optional<ExternalNoteSnapshot> fetchById(NoteSyncConnection conn, String externalId) {
        return fileStore.fetchById(conn, externalId);
    }

    @Override
    public String createExternal(NoteSyncConnection conn, Note note, String markdownContent) {
        return fileStore.createExternal(conn, note, markdownContent);
    }

    @Override
    public void updateExternal(NoteSyncConnection conn, String externalId, Note note, String markdownContent) {
        fileStore.updateExternal(conn, externalId, note, markdownContent);
    }

    @Override
    public void deleteExternal(NoteSyncConnection conn, String externalId) {
        fileStore.deleteExternal(conn, externalId);
    }

    @Override
    public SyncConnectionTestResponse testConnection(NoteSyncConnection conn) {
        Path root = fileStore.ensureRoot(conn);
        if (!Files.isDirectory(root)) {
            return SyncConnectionTestResponse.failure(conn.getId(), conn.getAuthMode(), "Folder is not a directory: " + root);
        }
        if (!Files.isReadable(root) || !Files.isWritable(root)) {
            return SyncConnectionTestResponse.failure(conn.getId(), conn.getAuthMode(), "Folder is not readable and writable: " + root);
        }
        return SyncConnectionTestResponse.success(conn.getId(), conn.getAuthMode(), "Local Markdown folder is readable and writable.");
    }
}
