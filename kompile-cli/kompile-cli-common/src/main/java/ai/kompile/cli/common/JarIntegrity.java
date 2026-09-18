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
package ai.kompile.cli.common;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * Cheap integrity probe for installed JAR artifacts.
 *
 * <p>Distribution installs have historically been corrupted by a disk-full
 * copy or archive extraction: the file exists but is a truncated zip, so
 * existence-only resolution launched it and the JVM failed later with
 * {@code Error: Invalid or corrupt jarfile}. This probe treats a
 * PK-prefixed file without an end-of-central-directory record as truncated,
 * and flags empty jars. Reading only the archive head and tail keeps it O(1)
 * even for multi-gigabyte exec jars.</p>
 */
public final class JarIntegrity {

    private static final byte[] ZIP_LOCAL_HEADER = {0x50, 0x4B, 0x03, 0x04}; // "PK\003\004"
    private static final byte[] ZIP_EOCD = {0x50, 0x4B, 0x05, 0x06};         // "PK\005\006"
    private static final int EOCD_MAX_SIZE = 22 + 0xFFFF;                    // record + max comment

    private JarIntegrity() {
    }

    /** True when {@link #describeProblem(File)} reports no problem. */
    public static boolean isUsableJar(File artifact) {
        return describeProblem(artifact) == null;
    }

    /**
     * Human-readable reason the artifact cannot be launched as a jar, or null when
     * usable (or when the artifact is absent / not a jar — absence and native
     * binaries are the caller's concern, not corruption).
     */
    public static String describeProblem(File artifact) {
        if (artifact == null || !artifact.isFile() || !artifact.getName().endsWith(".jar")) {
            return null;
        }
        if (!artifact.canRead()) {
            return artifact.getName() + " is not readable";
        }
        long size = artifact.length();
        if (size == 0) {
            return artifact.getName() + " is empty (0 bytes)";
        }
        if (!hasZipLocalHeader(artifact)) {
            // Not zip-prefixed: text placeholder (tests) or foreign payload — not our call.
            return null;
        }
        if (!hasEndOfCentralDirectory(artifact)) {
            return artifact.getName() + " is a truncated zip archive (" + size
                    + " bytes, missing end-of-central-directory)";
        }
        return null;
    }

    private static boolean hasZipLocalHeader(File jar) {
        try (RandomAccessFile file = new RandomAccessFile(jar, "r")) {
            if (file.length() < ZIP_LOCAL_HEADER.length) {
                return false;
            }
            byte[] header = new byte[ZIP_LOCAL_HEADER.length];
            file.readFully(header);
            return Arrays.equals(header, ZIP_LOCAL_HEADER);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasEndOfCentralDirectory(File jar) {
        try (RandomAccessFile file = new RandomAccessFile(jar, "r")) {
            long size = file.length();
            int window = (int) Math.min(size, EOCD_MAX_SIZE);
            file.seek(size - window);
            byte[] blob = new byte[window];
            file.readFully(blob);
            for (int i = blob.length - 22; i >= 0; i--) {
                if (blob[i] == ZIP_EOCD[0] && blob[i + 1] == ZIP_EOCD[1]
                        && blob[i + 2] == ZIP_EOCD[2] && blob[i + 3] == ZIP_EOCD[3]) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
