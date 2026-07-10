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
package ai.kompile.app.web.dto.karch;

/**
 * Summary info for a discovered Kompile Archive.
 */
public class ArchiveInfo {
    public String name;
    public String path;
    public String archiveId;
    public String version;
    public String description;
    public int modelCount;
    public long sizeBytes;
    public String lastModified;
    public boolean loaded;

    public ArchiveInfo(String name, String path, String archiveId, String version, String description,
                       int modelCount, long sizeBytes, String lastModified, boolean loaded) {
        this.name = name;
        this.path = path;
        this.archiveId = archiveId;
        this.version = version;
        this.description = description;
        this.modelCount = modelCount;
        this.sizeBytes = sizeBytes;
        this.lastModified = lastModified;
        this.loaded = loaded;
    }
}
