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
package ai.kompile.graph.reasoning.local;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AndroidAotDependencyContractTest {

    private static final String PATCH_SHA256 =
            "b31495be262f4a59130ba377641f84ca0c42c5ebcb78b7af1abdfb7ab1c9c202";

    @Test
    void graphNativeImagePinsAndVerifiesUnixFileAttributesAbi() throws Exception {
        Path module = Path.of(System.getProperty("user.dir"));
        String script = Files.readString(module.resolve("build-android-ndk.sh"));
        byte[] patchBytes = Files.readAllBytes(
                module.resolve("src/main/android/labsjdk-unix-file-attributes-abi.patch"));
        String patch = new String(patchBytes, StandardCharsets.UTF_8);

        assertEquals(PATCH_SHA256,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(patchBytes)));
        assertTrue(script.contains("EXPECTED_UNIX_FILE_ATTRIBUTES_FIELD=st_birthtime_sec"));
        assertTrue(script.contains("FORBIDDEN_UNIX_FILE_ATTRIBUTES_FIELD=st_birthtime_nsec"));
        assertTrue(script.contains("$GRAALVM_HOME/bin/javap\" -private sun.nio.fs.UnixFileAttributes"));
        assertTrue(script.contains("labsjdk-unix-file-attributes-abi.patch"));
        assertTrue(script.contains("LABSJDK_UNIX_FILE_ATTRIBUTES_PATCH_SHA256=" + PATCH_SHA256));
        assertTrue(script.contains("assert_unix_file_attributes_abi \"$JDK_LIB_DIR/libnio.a\""));
        assertTrue(script.contains("assert_unix_file_attributes_abi \"$FINAL_LIBRARY\""));

        assertTrue(patch.contains("-static jfieldID attrs_st_birthtime_nsec;"));
        assertTrue(patch.contains(
                "-    attrs_st_birthtime_nsec = (*env)->GetFieldID(env, clazz, \"st_birthtime_nsec\", \"J\");"));
        assertTrue(patch.contains(
                "-    (*env)->SetLongField(env, attrs, attrs_st_birthtime_nsec, (jlong)buf->stx_btime.tv_nsec);"));
        assertTrue(patch.contains(
                "     (*env)->SetLongField(env, attrs, attrs_st_birthtime_sec, (jlong)buf->stx_btime.tv_sec);"));
    }
}
