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

package ai.kompile.app.subprocess;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared JSON serialization helpers for subprocess args records.
 *
 * <p>Each {@code *SubprocessArgs} record previously declared its own private
 * {@code ObjectMapper} field and duplicated the same {@code fromFile}/{@code toFile}/
 * {@code writeToTempFile} logic. This utility centralises that pattern so each
 * record can delegate to a single implementation.</p>
 *
 * <p>{@link ObjectMapper} is thread-safe once constructed, so the shared instance
 * is safe to use from any thread.</p>
 */
public final class SubprocessArgsIo {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SubprocessArgsIo() {}

    /**
     * Returns the shared {@link ObjectMapper} used by this utility.
     *
     * <p>Callers that need to serialize/deserialize JSON strings (rather than files)
     * can use this mapper directly. The returned instance must not be mutated.</p>
     *
     * @return the shared mapper
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * Deserialize a subprocess args record from the given JSON file.
     *
     * @param path  path to the JSON file written by the parent process
     * @param type  the target record class
     * @param <T>   the record type
     * @return the deserialized instance
     * @throws IOException if the file cannot be read or JSON parsing fails
     */
    public static <T> T fromFile(Path path, Class<T> type) throws IOException {
        return MAPPER.readValue(Files.readString(path), type);
    }

    /**
     * Serialize {@code args} to the given path using pretty-printed JSON.
     *
     * @param path  destination file (will be created or overwritten)
     * @param args  the record to serialize
     * @throws IOException if writing fails
     */
    public static void toFile(Path path, Object args) throws IOException {
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(args));
    }

    /**
     * Serialize {@code args} to a new temporary file and return its path.
     *
     * <p>The caller is responsible for deleting the temp file when it is no
     * longer needed.</p>
     *
     * @param args    the record to serialize
     * @param prefix  temp-file name prefix (e.g. {@code "ingest-args-"})
     * @return path of the newly created temp file
     * @throws IOException if temp-file creation or writing fails
     */
    public static Path writeToTempFile(Object args, String prefix) throws IOException {
        Path tempFile = Files.createTempFile(prefix, ".json");
        toFile(tempFile, args);
        return tempFile;
    }
}
