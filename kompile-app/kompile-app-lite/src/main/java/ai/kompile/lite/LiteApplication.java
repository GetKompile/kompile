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

package ai.kompile.lite;

import ai.kompile.core.startup.Nd4jStartup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Kompile Lite — a lightweight, self-contained Chat + RAG + Graph RAG application.
 * <p>
 * This is a stripped-down alternative to the full kompile-app-main that bundles only
 * the essential components: embeddings, vector store, knowledge graph, LLM providers,
 * chat history, and a minimal web UI. No MCP servers, no pipeline orchestration,
 * no heavy Angular frontend.
 * <p>
 * Supports:
 * <ul>
 *   <li>Web server with chat UI at the configured port (default 8090)</li>
 *   <li>REST API for chat, document upload, graph operations</li>
 *   <li>GraalVM native image compilation</li>
 *   <li>Subprocess routing via --subprocess=TYPE flag</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = {
        "ai.kompile.lite",
        "ai.kompile.core",
        "ai.kompile.embedding.anserini",
        "ai.kompile.vectorstore.anserini",
        "ai.kompile.knowledgegraph",
        "ai.kompile.chat.history",
        "ai.kompile.staging",
        "ai.kompile.modelmanager",
        "ai.kompile.anserini",
        "ai.kompile.chunker",
        "ai.kompile.loader",
        "ai.kompile.tool.rag",
        "ai.kompile.llm"
})
public class LiteApplication {

    private static final Logger logger = LoggerFactory.getLogger(LiteApplication.class);

    public static void main(String[] args) throws Exception {
        // Route to subprocess if --subprocess=TYPE flag is present
        String subprocessType = Nd4jStartup.extractSubprocessType(args);
        if (subprocessType != null) {
            String[] forwardArgs = Nd4jStartup.stripSubprocessFlag(args);
            logger.info("Subprocess routing not supported in lite mode: {}", subprocessType);
            System.err.println("Subprocess routing is not supported in kompile-lite. Use kompile-app-main for subprocess features.");
            System.exit(1);
            return;
        }

        // Configure logging bridge for GraalVM native image
        Nd4jStartup.configureLog4jBridge();

        // Configure JavaCPP for native image mode BEFORE any ND4J calls
        Nd4jStartup.configureJavaCppForNativeImage();

        // Initialize ND4J backend
        Nd4jStartup.initializeNd4j();

        logger.info("Starting Kompile Lite...");
        SpringApplication.run(LiteApplication.class, args);
        logger.info("Kompile Lite is running!");
    }
}
