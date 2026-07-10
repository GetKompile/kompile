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

package ai.kompile.cli.main.lsp;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.LspTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Real-server smoke test — runs only when one of a handful of language servers is on
 * {@code PATH}, and skips cleanly otherwise. Never fails CI for a missing binary.
 */
class LspToolSmokeIT {

    @Test
    void symbolsAgainstAvailableServer(@TempDir Path tmp) throws Exception {
        LspServerRegistry registry = new LspServerRegistry(tmp.resolve("none.json"));
        String available = null;
        for (String lang : new String[]{"go", "cpp", "typescript", "rust"}) {
            LspServerConfig cfg = registry.forLanguage(lang);
            if (cfg != null && registry.isAvailable(cfg)) {
                available = lang;
                break;
            }
        }
        Assumptions.assumeTrue(available != null, "no language server binary on PATH; skipping smoke IT");

        Path file = writeFixture(tmp, available);

        LspTool tool = new LspTool();
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode params = om.createObjectNode();
        params.put("action", "symbols");
        params.put("file_path", tmp.relativize(file).toString());

        ToolResult result = tool.execute(params, context(tmp));
        assertNotNull(result, "tool must return a result even if the server is slow");
        System.out.println("[LspToolSmokeIT] " + available + " → " + result.getTitle()
                + "\n" + result.getOutput());
    }

    private static ToolContext context(Path workingDir) {
        PermissionService perms = new PermissionService();
        perms.setAutoApproveAll(true);
        return new ToolContext("smoke", null, perms, workingDir, null);
    }

    private static Path writeFixture(Path tmp, String language) throws IOException {
        switch (language) {
            case "go" -> {
                Files.writeString(tmp.resolve("go.mod"), "module smoke\n\ngo 1.20\n");
                Path go = tmp.resolve("main.go");
                Files.writeString(go, "package main\n\nfunc main() {}\n\nfunc Helper() int { return 1 }\n");
                return go;
            }
            case "typescript" -> {
                Files.writeString(tmp.resolve("tsconfig.json"), "{\"compilerOptions\":{}}\n");
                Path ts = tmp.resolve("app.ts");
                Files.writeString(ts, "export function hello(name: string): string { return name; }\n");
                return ts;
            }
            case "rust" -> {
                Files.writeString(tmp.resolve("Cargo.toml"),
                        "[package]\nname = \"smoke\"\nversion = \"0.1.0\"\nedition = \"2021\"\n");
                Files.createDirectories(tmp.resolve("src"));
                Path rs = tmp.resolve("src/main.rs");
                Files.writeString(rs, "fn main() {}\n\nfn helper() -> i32 { 1 }\n");
                return rs;
            }
            default -> {
                Path cpp = tmp.resolve("main.cpp");
                Files.writeString(cpp, "int add(int a, int b) { return a + b; }\n\nint main() { return 0; }\n");
                return cpp;
            }
        }
    }
}
