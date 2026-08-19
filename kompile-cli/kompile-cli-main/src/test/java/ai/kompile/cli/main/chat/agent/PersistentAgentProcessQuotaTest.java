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

package ai.kompile.cli.main.chat.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentAgentProcessQuotaTest {

    @Test
    void stderrQuotaMessageTerminatesTheJudgeInsteadOfLeavingItActive() throws Exception {
        Path script = Files.createTempFile("kompile-judge-quota-", ".sh");
        try {
            Files.writeString(script, """
                    #!/bin/sh
                    IFS= read -r init
                    printf '%s\\n' '{"type":"system","subtype":"init"}'
                    printf '%s\\n' "You've hit your limit for the current session." >&2
                    while IFS= read -r line; do
                      :
                    done
                    """);
            assertTrue(script.toFile().setExecutable(true), "test agent script must be executable");

            PersistentAgentProcess process = PersistentAgentProcess.builder(script.toString())
                    .skipPermissions(false)
                    .build();
            try {
                process.start(3);

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (process.isAlive() && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }

                assertFalse(process.isAlive(), "quota exhaustion must not leave the judge active");
                assertTrue(process.failureReason().toLowerCase().contains("limit"),
                        () -> "failure reason should expose quota exhaustion: " + process.failureReason());
            } finally {
                process.close();
            }
        } finally {
            Files.deleteIfExists(script);
        }
    }
}
