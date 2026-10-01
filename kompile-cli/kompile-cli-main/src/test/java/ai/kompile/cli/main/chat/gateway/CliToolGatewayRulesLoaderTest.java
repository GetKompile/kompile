/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat.gateway;

import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CLI gateway reads its rules from under the {@code user.home} current when it loads or
 * reloads them, not the one current when the class first loaded.
 */
@TemporaryUserHome
class CliToolGatewayRulesLoaderTest {

    @TempDir
    Path homes;

    @Test
    void rulesFollowTheCurrentHome() throws Exception {
        Path first = homes.resolve("first");
        Path second = homes.resolve("second");
        writeRules(first, "FIRST_PROMPT", "first_*");
        writeRules(second, "SECOND_PROMPT", "second_*");
        String original = System.getProperty("user.home");
        try {
            // The class can first load while an earlier home is current.
            System.setProperty("user.home", first.toString());
            CliToolGatewayRulesLoader loader = new CliToolGatewayRulesLoader(new ObjectMapper());
            assertEquals("FIRST_PROMPT", loader.getSystemPrompt());
            assertEquals(1, loader.getMatchingRules("first_tool").size());

            System.setProperty("user.home", second.toString());
            assertEquals("SECOND_PROMPT", new CliToolGatewayRulesLoader(new ObjectMapper()).getSystemPrompt());
            loader.reload();
            assertEquals("SECOND_PROMPT", loader.getSystemPrompt());
            assertTrue(loader.getMatchingRules("first_tool").isEmpty());
            assertEquals(1, loader.getMatchingRules("second_tool").size());

            System.setProperty("user.home", homes.resolve("without-rules").toString());
            loader.reload();
            assertNull(loader.getSystemPrompt());
            assertTrue(loader.getMatchingRules("second_tool").isEmpty());
        } finally {
            if (original == null) System.clearProperty("user.home");
            else System.setProperty("user.home", original);
        }
    }

    private static void writeRules(Path home, String prompt, String toolPattern) throws Exception {
        Path config = home.resolve(".kompile").resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("tool-gateway-rules.json"), "{\"systemPrompt\":\"" + prompt
                + "\",\"rules\":[{\"name\":\"rule\",\"toolPatterns\":[\"" + toolPattern + "\"]}]}");
    }
}
