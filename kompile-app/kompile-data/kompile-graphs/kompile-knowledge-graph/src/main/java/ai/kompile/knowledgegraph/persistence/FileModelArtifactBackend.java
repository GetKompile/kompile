/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link ModelArtifactBackend} that stores every artifact type under the local
 * filesystem at:
 * <pre>
 *   &lt;dataDir&gt;/data/graph/reasoning/&lt;factSheetId&gt;/&lt;type.name().toLowerCase()&gt;/&lt;artifactId&gt;
 * </pre>
 *
 * <p>Priority {@code 0} — this backend is the fallback when no higher-priority backend
 * (e.g. {@link StagingModelArtifactBackend}) claims the artifact type.</p>
 */
@Slf4j
@Component
public class FileModelArtifactBackend implements ModelArtifactBackend {

    @Value("${kompile.data.dir:}")
    private String dataDir;

    // ── ModelArtifactBackend ──────────────────────────────────────────────────

    @Override
    public boolean supports(ModelArtifactType type) {
        // Handles every artifact type — this is the universal file backend.
        return true;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public void store(ModelArtifactRef ref, Path sourceFile) throws IOException {
        Path target = artifactPath(ref);
        Files.createDirectories(target.getParent());
        Files.copy(sourceFile, target, StandardCopyOption.REPLACE_EXISTING);
        log.debug("FileModelArtifactBackend: stored {} → {}", ref, target);
    }

    @Override
    public Path retrieve(ModelArtifactRef ref, Path targetFile) throws IOException {
        Path source = artifactPath(ref);
        if (!Files.exists(source)) {
            throw new NoSuchFileException(source.toString(),
                    null, "Artifact not found in file backend: " + ref);
        }
        Files.createDirectories(targetFile.getParent());
        Files.copy(source, targetFile, StandardCopyOption.REPLACE_EXISTING);
        log.debug("FileModelArtifactBackend: retrieved {} → {}", ref, targetFile);
        return targetFile;
    }

    @Override
    public List<ModelArtifactRef> list(String factSheetId) {
        Path base = reasoningBase().resolve(factSheetId);
        List<ModelArtifactRef> refs = new ArrayList<>();
        if (!Files.isDirectory(base)) {
            return refs;
        }
        try (var stream = Files.walk(base, 3)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                // Path structure: <factSheetId>/<typeLower>/<artifactId>
                Path rel = base.relativize(p);
                if (rel.getNameCount() >= 2) {
                    String typeLower = rel.getName(0).toString();
                    String artifactId = rel.subpath(1, rel.getNameCount()).toString();
                    ModelArtifactType type = typeFromDirName(typeLower);
                    if (type != null) {
                        refs.add(new ModelArtifactRef(factSheetId, type, artifactId, null));
                    }
                }
            });
        } catch (IOException e) {
            log.warn("FileModelArtifactBackend: could not list artifacts for factSheet {} — {}",
                    factSheetId, e.getMessage());
        }
        return refs;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Path resolveBase() {
        return (dataDir == null || dataDir.isBlank())
                ? Path.of(System.getProperty("user.home"), ".kompile")
                : Path.of(dataDir);
    }

    private Path reasoningBase() {
        return resolveBase().resolve("data").resolve("graph").resolve("reasoning");
    }

    private Path artifactPath(ModelArtifactRef ref) {
        return reasoningBase()
                .resolve(ref.factSheetId())
                .resolve(ref.type().name().toLowerCase())
                .resolve(ref.artifactId());
    }

    private static ModelArtifactType typeFromDirName(String dirName) {
        for (ModelArtifactType t : ModelArtifactType.values()) {
            if (t.name().toLowerCase().equals(dirName)) {
                return t;
            }
        }
        return null;
    }
}
