/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.chat.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OpenCodeModelMetadataResolverTest {

    private static final String VERBOSE_OUTPUT = """
            opencode/deepseek-v4-flash-free
            {
              "id": "deepseek-v4-flash-free",
              "providerID": "opencode",
              "name": "DeepSeek V4 Flash Free",
              "limit": {
                "context": 200000,
                "output": 128000
              },
              "capabilities": {
                "toolcall": true,
                "interleaved": {
                  "field": "reasoning_content"
                }
              }
            }
            opencode/big-pickle
            {
              "id": "big-pickle",
              "providerID": "opencode",
              "limit": {
                "context": 200000,
                "input": 160000,
                "output": 32000
              }
            }
            opencode/claude-haiku-4-5
            {
              "id": "claude-haiku-4-5",
              "providerID": "opencode",
              "limit": {
                "context": 200000,
                "output": 64000
              }
            }
            opencode/gpt-5
            {
              "id": "gpt-5",
              "providerID": "opencode",
              "limit": {
                "context": 400000,
                "input": 272000,
                "output": 128000
              }
            }
            opencode-go/deepseek-v4-flash
            {
              "id": "deepseek-v4-flash",
              "providerID": "opencode-go",
              "limit": {
                "context": 1000000,
                "output": 384000
              }
            }
            github-copilot/claude-sonnet-4.5
            {
              "id": "claude-sonnet-4.5",
              "providerID": "github-copilot",
              "limit": {
                "context": 200000,
                "input": 168000,
                "output": 32000
              }
            }
            github-copilot/gpt-5.5
            {
              "id": "gpt-5.5",
              "providerID": "github-copilot",
              "limit": {
                "context": 400000,
                "input": 272000,
                "output": 128000
              }
            }
            opencode/kimi-k2.5
            {
              "id": "kimi-k2.5",
              "providerID": "opencode",
              "limit": {
                "context": 256000,
                "output": 64000
              }
            }
            """;

    @TempDir
    Path tempDir;

    @Test
    void parsesVerboseOpenCodeModelLimits() {
        Optional<OpenCodeModelMetadataResolver.ModelMetadata> metadata =
                OpenCodeModelMetadataResolver.parseVerboseModel(
                        VERBOSE_OUTPUT,
                        "opencode",
                        "opencode/deepseek-v4-flash-free");

        assertTrue(metadata.isPresent());
        assertEquals("opencode", metadata.get().providerId());
        assertEquals("deepseek-v4-flash-free", metadata.get().modelId());
        assertEquals(200_000, metadata.get().contextWindow());
        assertEquals(128_000, metadata.get().maxOutputTokens());
    }

    @ParameterizedTest
    @CsvSource({
            "opencode,big-pickle,200000,32000",
            "opencode,claude-haiku-4-5,200000,64000",
            "opencode,gpt-5,400000,128000",
            "opencode-go,deepseek-v4-flash,1000000,384000",
            "github-copilot,claude-sonnet-4.5,200000,32000",
            "github-copilot,gpt-5.5,400000,128000"
    })
    void parsesVerboseOpenCodeModelLimitsAcrossProviders(String provider,
                                                          String model,
                                                          int expectedContext,
                                                          int expectedOutput) {
        Optional<OpenCodeModelMetadataResolver.ModelMetadata> metadata =
                OpenCodeModelMetadataResolver.parseVerboseModel(VERBOSE_OUTPUT, provider, model);

        assertTrue(metadata.isPresent());
        assertEquals(provider, metadata.get().providerId());
        assertEquals(model, metadata.get().modelId());
        assertEquals(expectedContext, metadata.get().contextWindow());
        assertEquals(expectedOutput, metadata.get().maxOutputTokens());
    }

    @Test
    void ignoresMetadataForDifferentProviderOrModel() {
        Optional<OpenCodeModelMetadataResolver.ModelMetadata> metadata =
                OpenCodeModelMetadataResolver.parseVerboseModel(
                        VERBOSE_OUTPUT,
                        "opencode-go",
                        "deepseek-v4-flash-free");

        assertTrue(metadata.isEmpty());
    }

    @Test
    void resolvesVerboseOutputFromConfiguredExecutable() throws Exception {
        Path executable = tempDir.resolve("fake-opencode");
        Files.writeString(executable, """
                #!/usr/bin/env bash
                cat <<'OUT'
                opencode/deepseek-v4-flash-free
                {
                  "id": "deepseek-v4-flash-free",
                  "providerID": "opencode",
                  "limit": {
                    "context": 200000,
                    "output": 128000
                  }
                }
                OUT
                """);
        executable.toFile().setExecutable(true);

        String previous = System.getProperty("kompile.opencode.executable");
        try {
            System.setProperty("kompile.opencode.executable", executable.toString());
            OpenCodeModelMetadataResolver.clearCacheForTests();

            Optional<OpenCodeModelMetadataResolver.ModelMetadata> metadata =
                    OpenCodeModelMetadataResolver.resolve(
                            "opencode",
                            "deepseek-v4-flash-free",
                            Duration.ofSeconds(5));

            assertTrue(metadata.isPresent());
            assertEquals(200_000, metadata.get().contextWindow());
            assertEquals(128_000, metadata.get().maxOutputTokens());
        } finally {
            if (previous == null) {
                System.clearProperty("kompile.opencode.executable");
            } else {
                System.setProperty("kompile.opencode.executable", previous);
            }
            OpenCodeModelMetadataResolver.clearCacheForTests();
        }
    }

    @Test
    void probesInstalledOpenCodeMetadataWhenAvailable() {
        OpenCodeModelMetadataResolver.clearCacheForTests();

        Optional<OpenCodeModelMetadataResolver.ModelMetadata> metadata =
                OpenCodeModelMetadataResolver.resolve(
                        "opencode",
                        "deepseek-v4-flash-free",
                        Duration.ofSeconds(20));

        assumeTrue(metadata.isPresent(), "OpenCode CLI metadata is not available in this environment");
        assertEquals("opencode", metadata.get().providerId());
        assertEquals("deepseek-v4-flash-free", metadata.get().modelId());
        assertTrue(metadata.get().contextWindow() > 0);
        assertTrue(metadata.get().maxOutputTokens() > 0);
    }

    @ParameterizedTest
    @CsvSource({
            "opencode,big-pickle",
            "opencode,claude-haiku-4-5",
            "opencode,deepseek-v4-flash-free",
            "opencode,gpt-5",
            "opencode,gpt-5.5",
            "opencode-go,deepseek-v4-flash",
            "opencode-go,kimi-k2.7-code",
            "github-copilot,claude-sonnet-4.5",
            "github-copilot,gpt-5.5"
    })
    void probesInstalledOpenCodeMetadataForSeveralModelsWhenAvailable(String provider,
                                                                       String model) {
        OpenCodeModelMetadataResolver.clearCacheForTests();

        Optional<OpenCodeModelMetadataResolver.ModelMetadata> metadata =
                OpenCodeModelMetadataResolver.resolve(provider, model, Duration.ofSeconds(20));

        assumeTrue(metadata.isPresent(),
                "OpenCode CLI metadata for " + provider + "/" + model + " is not available in this environment");
        assertEquals(provider, metadata.get().providerId());
        assertEquals(model, metadata.get().modelId());
        assertTrue(metadata.get().contextWindow() > 0);
        assertTrue(metadata.get().maxOutputTokens() > 0);
        assertTrue(metadata.get().maxOutputTokens() <= metadata.get().contextWindow());
    }
}
