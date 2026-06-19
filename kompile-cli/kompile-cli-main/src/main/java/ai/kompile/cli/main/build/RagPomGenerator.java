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

package ai.kompile.cli.main.build;

import ai.kompile.cli.main.build.config.BuildPreset;
import ai.kompile.cli.main.build.config.ModuleSelection;
import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.modelmanager.ModelConstants;
import ai.kompile.modelmanager.ModelDescriptor;
import ai.kompile.modelmanager.ModelType;
import org.apache.maven.model.*;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import picocli.CommandLine;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Deprecated(since = "0.2.0", forRemoval = true)
@CommandLine.Command(name = "rag-pom-generate", mixinStandardHelpOptions = false, description = "Generates a pom.xml for a RAG MCP Assistant application instance. DEPRECATED: Use 'kompile build app' instead.")
public class RagPomGenerator implements Callable<Void> {

    // ##################################################################################
    // UPDATED: Default values for options are modified to match the target pom.xml
    // ##################################################################################

    @CommandLine.Option(names = {
            "--databaseUrl" }, description = "Database URL. Will auto-create database if it doesn't exist", defaultValue = "jdbc:postgresql://localhost:5432/kompile_db")
    private String databaseUrl = "jdbc:postgresql://localhost:5432/kompile_db";

    @CommandLine.Option(names = { "--databaseUsername" }, description = "Database username", defaultValue = "postgres")
    private String databaseUsername = "postgres";

    @CommandLine.Option(names = { "--databasePassword" }, description = "Database password", defaultValue = "postgres")
    private String databasePassword = "postgres";

    @CommandLine.Option(names = {
            "--enableSchemaInit" }, description = "Enable automatic schema initialization with SQL scripts", defaultValue = "true")
    private boolean enableSchemaInit = true;
    private static final String DEFAULT_EMBEDDED_POSTGRES_VERSION = "2.0.7";

    @CommandLine.Option(names = {
            "--outputFile" }, description = "The output file for the generated pom.xml", defaultValue = "pom-rag-instance.xml")
    private File outputFile;

    @CommandLine.Option(names = {
            "--instanceGroupId" }, description = "GroupId for the generated RAG instance", defaultValue = "ai.kompile.rag.instance")
    private String instanceGroupId;

    @CommandLine.Option(names = {
            "--instanceArtifactId" }, description = "ArtifactId for the generated RAG instance", defaultValue = "kompile-sample")
    private String instanceArtifactId;

    @CommandLine.Option(names = {
            "--instanceVersion" }, description = "Version for the generated RAG instance", defaultValue = "0.1.0-SNAPSHOT")
    private String instanceVersion;

    @CommandLine.Option(names = {
            "--ragMcpVersion" }, description = "Version of the ai.kompile modules", defaultValue = "0.1.0-SNAPSHOT")
    private String ragMcpVersion;

    @CommandLine.Option(names = {
            "--includeAppMain" }, description = "Include kompile-app-main module", defaultValue = "true", negatable = true)
    private boolean includeAppMain;
    @CommandLine.Option(names = {
            "--includeAppCore" }, description = "Include kompile-app-core module", defaultValue = "true", negatable = true)
    private boolean includeAppCore;
    @CommandLine.Option(names = {
            "--includeLoadersOrchestrator" }, description = "Include kompile-app-loaders-orchestrator module", defaultValue = "true", negatable = true)
    private boolean includeLoadersOrchestrator;

    @CommandLine.Option(names = {
            "--includeLoaderTika" }, description = "Include kompile-loader-tika module", defaultValue = "true", negatable = true)
    private boolean includeLoaderTika;
    @CommandLine.Option(names = { "--includeLoaderPdf" }, description = "Include kompile-loader-pdf-extended module", defaultValue = "true", negatable = true)
    private boolean includeLoaderPdf;
    @CommandLine.Option(names = {
            "--includeLoaderMicrosoft" }, description = "Include kompile-loader-microsoft module for Office documents", defaultValue = "true", negatable = true)
    private boolean includeLoaderMicrosoft;
    @CommandLine.Option(names = {
            "--includeLoaderMail" }, description = "Include kompile-loader-mail module for email parsing", defaultValue = "true", negatable = true)
    private boolean includeLoaderMail;
    @CommandLine.Option(names = {
            "--includeLoaderPdfExtended" }, description = "Include kompile-loader-pdf-extended module for advanced PDF processing", defaultValue = "true", negatable = true)
    private boolean includeLoaderPdfExtended;

    @CommandLine.Option(names = {
            "--includeAnserini" }, description = "Include kompile-app-anserini module", defaultValue = "true", negatable = true)
    private boolean includeAnserini;
    @CommandLine.Option(names = {
            "--includeEmbeddingAnserini" }, description = "Include kompile-embedding-anserini module for BGE, Arctic Embed, and other SameDiff-based embeddings", defaultValue = "true", negatable = true)
    private boolean includeEmbeddingAnserini;
    @CommandLine.Option(names = {
            "--includeVectorStoreAnserini" }, description = "Include kompile-vectorstore-anserini", defaultValue = "true", negatable = true)
    private boolean includeVectorStoreAnserini;

    @CommandLine.Option(names = {
            "--includeLlmOpenai" }, description = "Include kompile-app-openai-llm module", defaultValue = "true", negatable = true)
    private boolean includeLlmOpenai;
    @CommandLine.Option(names = { "--includeLlmAnthropic" }, description = "Include kompile-app-anthropic-llm module", defaultValue = "true", negatable = true)
    private boolean includeLlmAnthropic;
    @CommandLine.Option(names = { "--includeLlmGemini" }, description = "Include kompile-app-gemini-llm module", defaultValue = "true", negatable = true)
    private boolean includeLlmGemini;
    @CommandLine.Option(names = {
            "--includeEmbeddingOpenai" }, description = "Include kompile-embedding-openai module", defaultValue = "true", negatable = true)
    private boolean includeEmbeddingOpenai;
    @CommandLine.Option(names = {
            "--includeEmbeddingSentenceTransformer" }, description = "Include kompile-embedding-sentence-transformer module", defaultValue = "true", negatable = true)
    private boolean includeEmbeddingSentenceTransformer;

    @CommandLine.Option(names = {
            "--includeVectorstoreChroma" }, description = "Include kompile-vectorstore-chroma module")
    private boolean includeVectorstoreChroma = false;
    @CommandLine.Option(names = {
            "--includeVectorstorePgvector" }, description = "Include kompile-vectorstore-pgvector module", defaultValue = "false", negatable = true)
    private boolean includeVectorstorePgvector;

    @CommandLine.Option(names = {
            "--includeToolFilesystem" }, description = "Include kompile-tool-filesystem module", defaultValue = "true", negatable = true)
    private boolean includeToolFilesystem;
    @CommandLine.Option(names = {
            "--includeToolRag" }, description = "Include kompile-tool-rag module", defaultValue = "true", negatable = true)
    private boolean includeToolRag;

    @CommandLine.Option(names = {
            "--includeToolTableSearch" }, description = "Include kompile-tool-table-search module for MCP table search operations", defaultValue = "true", negatable = true)
    private boolean includeToolTableSearch;

    @CommandLine.Option(names = {
            "--includeKvCache" }, description = "Include kompile-kvcache module for KV cache management", defaultValue = "true", negatable = true)
    private boolean includeKvCache;

    @CommandLine.Option(names = {
            "--includeRagPipeline" }, description = "Include kompile-rag-pipeline module for end-to-end RAG pipeline management", defaultValue = "true", negatable = true)
    private boolean includeRagPipeline;

    @CommandLine.Option(names = {
            "--includeGraphAlgorithms" }, description = "Include kompile-graph-algorithms module (PageRank, communities, shortest path)", defaultValue = "true", negatable = true)
    private boolean includeGraphAlgorithms;

    @CommandLine.Option(names = {
            "--includeKnowledgeGraph" }, description = "Include kompile-knowledge-graph module for knowledge graph integration", defaultValue = "true", negatable = true)
    private boolean includeKnowledgeGraph;

    @CommandLine.Option(names = {
            "--includeOcr" }, description = "Include OCR/VLM modules (ocr-core, ocr-models, ocr-postprocess, ocr-integration, ocr-datapipeline)", defaultValue = "true", negatable = true)
    private boolean includeOcr;

    @CommandLine.Option(names = {
            "--includeCrawlGraph" }, description = "Include kompile-crawl-graph module for unified crawl-to-graph extraction", defaultValue = "true", negatable = true)
    private boolean includeCrawlGraph;

    @CommandLine.Option(names = {
            "--includeCrawlerCore" }, description = "Include kompile-crawler-core for web/file/HTML crawling", defaultValue = "true", negatable = true)
    private boolean includeCrawlerCore;

    @CommandLine.Option(names = {
            "--includeEventAttribution" }, description = "Include kompile-event-attribution for Bayesian/MEBN probabilistic inference", defaultValue = "true", negatable = true)
    private boolean includeEventAttribution;

    @CommandLine.Option(names = {
            "--includeProcessEngine" }, description = "Include kompile-process-engine for business process execution", defaultValue = "false", negatable = true)
    private boolean includeProcessEngine;

    @CommandLine.Option(names = {
            "--includeProcessDiscovery" }, description = "Include kompile-process-discovery for LLM-based process discovery", defaultValue = "true", negatable = true)
    private boolean includeProcessDiscovery;

    @CommandLine.Option(names = {
            "--includeCodeIndexer" }, description = "Include kompile-code-indexer for managed code project indexing", defaultValue = "true", negatable = true)
    private boolean includeCodeIndexer;

    @CommandLine.Option(names = {
            "--includeDataEnrichment" }, description = "Include kompile-data-enrichment for LLM-based data enrichment and labeling", defaultValue = "true", negatable = true)
    private boolean includeDataEnrichment;

    @CommandLine.Option(names = {
            "--includeEmbeddingPostgresml" }, description = "Include kompile-embedding-postgresml module")
    private boolean includeEmbeddingPostgresml = false;
    @CommandLine.Option(names = { "--includePgmlIndexer" }, description = "Include kompile-app-pgml-indexer module")
    private boolean includePgmlIndexer = false;

    @CommandLine.Option(names = {
            "--includeChunkerSentence" }, description = "Include kompile-chunker-sentence module.", defaultValue = "true", negatable = true)
    private boolean includeChunkerSentence;
    @CommandLine.Option(names = {
            "--includeChunkerRecursiveCharacter" }, description = "Include kompile-chunker-recursivecharacter module", defaultValue = "true", negatable = true)
    private boolean includeChunkerRecursiveCharacter;
    @CommandLine.Option(names = { "--includeChunkerMarkdown" }, description = "Include kompile-chunker-markdown module", defaultValue = "true", negatable = true)
    private boolean includeChunkerMarkdown;
    @CommandLine.Option(names = { "--includeChunkerToken" }, description = "Include kompile-chunker-token module", defaultValue = "true", negatable = true)
    private boolean includeChunkerToken;

    @CommandLine.Option(names = {
            "--javacppPlatform" }, description = "Build for a specific specified platform. An example would be linux-x86_64 - this reduces binary size and prevents out of memories from trying to include binaries for too many platforms.")
    private String javacppPlatform = "linux-x86_64";

    @CommandLine.Option(names = {
            "--javacppExtension" }, description = "An optional javacpp extension such as avx2 or cuda depending on the target set of dependencies.")
    private String javacppExtension;

    @CommandLine.Option(names = {
            "--supportedLanguages" }, description = "Comma-separated list of ISO 639-1 language codes (e.g., en,de,es). "
                    +
                    "Used to determine which OpenNLP sentence models to download if --includeChunkerSentence is active. "
                    +
                    "The first language in this list will also be set as the default 'kompile.opennlp.sentence.language' property.", defaultValue = "en", split = ",")
    private List<String> supportedLanguages = new ArrayList<>(Collections.singletonList("en"));

    @CommandLine.Option(names = {
            "--buildNative" }, description = "Configure build for GraalVM native image", defaultValue = "true")
    private boolean buildNative = true;

    @CommandLine.Option(names = {
            "--appTitle" }, description = "Application title displayed in the UI banner", defaultValue = "Kompile RAG Console")
    private String appTitle = "Kompile RAG Console";

    @CommandLine.Option(names = {
            "--anserini-indexes" }, description = "Comma-separated list of Anserini prebuilt index IDs to ensure are available (e.g., msmarco-passage-v1). "
                    +
                    "Requires --includeAnserini to be true. Consult ModelConstants.java for available IDs.", split = ",", arity = "0..*")
    private List<String> anseriniIndexIds = new ArrayList<>();

    private KompileModelManager modelManager;
    private Map<String, Path> resolvedModelPaths = new HashMap<>();

    @CommandLine.Option(names = {
            "--anserini-encoders" }, description = "Comma-separated list of Anserini encoder model IDs (e.g., bge-base-en-v1.5-onnx, splade-pp-sd-onnx). "
                    +
                    "Requires --includeAnserini to be true. Consult ModelConstants.java for available IDs.", split = ",", arity = "0..*")
    private List<String> anseriniEncoderModelIds = new ArrayList<>();

    private Model model;
    private final List<Dependency> defaultDependencies = new ArrayList<>();

    /** ND4J backend artifactId (e.g. "nd4j-cuda-12.9" or "nd4j-native"). Set by configureFrom(). */
    private String backend = "nd4j-cuda-12.9";

    private static final String DEFAULT_SPRING_BOOT_VERSION = "3.4.5";
    private static final String DEFAULT_SPRING_AI_VERSION = "1.0.0";
    private static final String DEFAULT_LOMBOK_VERSION = "1.18.38";
    private static final String DEFAULT_JACKSON_VERSION = "2.15.3";
    private static final String DEFAULT_GUAVA_VERSION = "32.1.3-jre";
    private static final String DEFAULT_LOG4J_VERSION = "2.24.3";
    private static final String DEFAULT_MAVEN_COMPILER_PLUGIN_VERSION = "3.13.0";
    private static final String DEFAULT_MAVEN_RESOURCES_PLUGIN_VERSION = "3.3.1";
    private static final String DEFAULT_MAVEN_JAR_PLUGIN_VERSION = "3.3.0";
    private static final String DEFAULT_POSTGRES_VERSION = "42.7.5";
    private static final String DEFAULT_NATIVE_MAVEN_PLUGIN_VERSION = "0.10.6";
    private static final String DEFAULT_BUILD_HELPER_MAVEN_PLUGIN_VERSION = "3.6.0";
    private static final String DEFAULT_JAKARTA_MAIL_VERSION = "2.1.3";
    private static final String CORE_APP_MAIN_CLASS_FQCN = "ai.kompile.app.MainApplication";

    private static final String OPENNLP_MODEL_TARGET_DIR_IN_RESOURCES = "models";
    private static final String OPENNLP_MODEL_BASE_URL = "https://dlcdn.apache.org/opennlp/models/ud-models-1.2/";

    private static final Map<String, String> LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME = new LinkedHashMap<>();
    private static final Map<String, String> LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME = new LinkedHashMap<>();

    static {
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("bg", "opennlp-bg-ud-btb-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("bg", "bg-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("ca", "opennlp-ca-ud-ancora-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("ca", "ca-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("cs", "opennlp-cs-ud-pdt-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("cs", "cs-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("da", "opennlp-da-ud-ddt-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("da", "da-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("de", "opennlp-de-ud-gsd-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("de", "de-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("el", "opennlp-el-ud-gdt-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("el", "el-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("en", "opennlp-en-ud-ewt-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("en", "en-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("es", "opennlp-es-ud-gsd-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("es", "es-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("et", "opennlp-et-ud-edt-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("et", "et-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("eu", "opennlp-eu-ud-bdt-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("eu", "eu-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("fi", "opennlp-fi-ud-tdt-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("fi", "fi-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("fr", "opennlp-fr-ud-gsd-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("fr", "fr-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("hr", "opennlp-hr-ud-set-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("hr", "hr-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("hy", "opennlp-hy-ud-bsut-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("hy", "hy-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("is", "opennlp-is-ud-icepahc-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("is", "is-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("it", "opennlp-it-ud-vit-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("it", "it-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("ka", "opennlp-ka-ud-glc-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("ka", "ka-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("kk", "opennlp-kk-ud-ktb-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("kk", "kk-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("ko", "opennlp-ko-ud-kaist-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("ko", "ko-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("lv", "opennlp-lv-ud-lvtb-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("lv", "lv-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("nl", "opennlp-nl-ud-alpino-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("nl", "nl-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("no", "opennlp-no-ud-bokmaal-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("no", "no-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("pl", "opennlp-pl-ud-pdb-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("pl", "pl-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("pt", "opennlp-pt-ud-gsd-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("pt", "pt-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("ro", "opennlp-ro-ud-rrt-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("ro", "ro-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("ru", "opennlp-ru-ud-gsd-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("ru", "ru-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("sk", "opennlp-sk-ud-snk-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("sk", "sk-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("sl", "opennlp-sl-ud-ssj-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("sl", "sl-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("sr", "opennlp-sr-ud-set-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("sr", "sr-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("sv", "opennlp-sv-ud-talbanken-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("sv", "sv-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("tr", "opennlp-tr-ud-boun-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("tr", "tr-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.put("uk", "opennlp-uk-ud-iu-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.put("uk", "uk-sent.bin");
    }

    /**
     * Programmatically configure this generator from a {@link ModuleSelection} and explicit parameters.
     * Used by {@link ai.kompile.cli.main.build.InitProjectCommand} to drive generation without CLI parsing.
     */
    public void configureFrom(ModuleSelection modules,
                              String projectName, String instanceGroupId, String instanceVersion,
                              String ragMcpVersion, File outputFile,
                              String javacppPlatform, String javacppExtension,
                              String databaseUrl, String databaseUsername, String databasePassword,
                              boolean enableSchemaInit, boolean buildNative,
                              String appTitle, List<String> supportedLanguages,
                              List<String> anseriniIndexIds, List<String> anseriniEncoderModelIds,
                              int serverPort, String backend,
                              BuildPreset.BackendAffinity backendAffinity) {
        this.instanceArtifactId = projectName;
        this.instanceGroupId = instanceGroupId;
        this.instanceVersion = instanceVersion;
        this.ragMcpVersion = ragMcpVersion;
        this.outputFile = outputFile;
        this.javacppPlatform = javacppPlatform != null ? javacppPlatform : "linux-x86_64";
        this.javacppExtension = javacppExtension;
        this.databaseUrl = databaseUrl;
        this.databaseUsername = databaseUsername;
        this.databasePassword = databasePassword;
        this.enableSchemaInit = enableSchemaInit;
        this.buildNative = buildNative;
        this.backend = backend != null ? backend : "nd4j-cuda-12.9";
        this.appTitle = appTitle != null ? appTitle : "Kompile RAG Console";
        this.supportedLanguages = supportedLanguages != null ? supportedLanguages : Collections.singletonList("en");
        this.anseriniIndexIds = anseriniIndexIds != null ? anseriniIndexIds : new ArrayList<>();
        this.anseriniEncoderModelIds = anseriniEncoderModelIds != null ? anseriniEncoderModelIds : new ArrayList<>();

        // Map ModuleSelection to the individual include* flags
        Set<String> ids = modules.getAll();
        this.includeAppMain = ids.contains("app-main");
        this.includeAppCore = ids.contains("app-core");
        this.includeLoadersOrchestrator = ids.contains("loaders-orchestrator");
        this.includeLoaderTika = ids.contains("loader-tika");
        this.includeLoaderPdf = ids.contains("loader-pdf");
        this.includeLoaderMicrosoft = ids.contains("loader-microsoft");
        this.includeLoaderMail = ids.contains("loader-mail");
        this.includeLoaderPdfExtended = ids.contains("loader-pdf-extended");
        this.includeAnserini = ids.contains("app-anserini");
        this.includeEmbeddingAnserini = ids.contains("embedding-anserini");
        this.includeVectorStoreAnserini = ids.contains("vectorstore-anserini");
        this.includeLlmOpenai = ids.contains("llm-openai");
        this.includeLlmAnthropic = ids.contains("llm-anthropic");
        this.includeLlmGemini = ids.contains("llm-gemini");
        this.includeEmbeddingOpenai = ids.contains("embedding-openai");
        this.includeEmbeddingSentenceTransformer = ids.contains("embedding-sentence-transformer");
        this.includeVectorstoreChroma = ids.contains("vectorstore-chroma");
        this.includeVectorstorePgvector = ids.contains("vectorstore-pgvector");
        this.includeToolFilesystem = ids.contains("tool-filesystem");
        this.includeToolRag = ids.contains("tool-rag");
        this.includeKvCache = ids.contains("kvcache");
        this.includeRagPipeline = ids.contains("rag-pipeline") || ids.contains("pipeline-management");
        this.includeGraphAlgorithms = ids.contains("graph-algorithms");
        this.includeKnowledgeGraph = ids.contains("knowledge-graph");
        this.includeOcr = ids.contains("ocr-core");
        this.includeCrawlGraph = ids.contains("crawl-graph");
        this.includeCrawlerCore = ids.contains("crawler-core");
        this.includeEventAttribution = ids.contains("event-attribution");
        this.includeProcessEngine = ids.contains("process-engine");
        this.includeProcessDiscovery = ids.contains("process-discovery");
        this.includeCodeIndexer = ids.contains("code-indexer");
        this.includeDataEnrichment = ids.contains("data-enrichment");
        this.includeEmbeddingPostgresml = ids.contains("embedding-postgresml");
        this.includePgmlIndexer = ids.contains("pgml-indexer");
        this.includeChunkerSentence = ids.contains("chunker-sentence");
        this.includeChunkerRecursiveCharacter = ids.contains("chunker-recursive-character");
        this.includeChunkerMarkdown = ids.contains("chunker-markdown");
        this.includeChunkerToken = ids.contains("chunker-token");
    }

    private Dependency createDependencyInternal(String groupId, String artifactId, String versionProperty, String scope,
            String classifier, boolean optional) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        if (versionProperty != null) {
            dependency.setVersion(versionProperty);
        }
        if (scope != null && !scope.isEmpty()) {
            dependency.setScope(scope);
        }
        if (classifier != null && !classifier.isEmpty()) {
            dependency.setClassifier(classifier);
        }
        if (optional) {
            dependency.setOptional(true);
        }
        return dependency;
    }

    private void addDependency(List<Dependency> addTo, String groupId, String artifactId, String versionProperty,
            String scope, String classifier, boolean optional) {
        addTo.add(createDependencyInternal(groupId, artifactId, versionProperty, scope, classifier, optional));
    }

    private void addDependency(List<Dependency> addTo, String groupId, String artifactId, String versionProperty) {
        addDependency(addTo, groupId, artifactId, versionProperty, "compile", null, false);
    }

    private Xpp3Dom addChild(Xpp3Dom parent, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        if (value != null) {
            child.setValue(value);
        }
        if (parent != null) {
            parent.addChild(child);
        }
        return child;
    }

    private void addBuildArg(Xpp3Dom buildArgsDom, String argValue) {
        if (buildArgsDom == null)
            return;
        Xpp3Dom buildArgElement = new Xpp3Dom("buildArg");
        buildArgElement.setValue(argValue);
        buildArgsDom.addChild(buildArgElement);
    }

    private List<String> downloadOpenNLPModelsForSupportedLanguages(File projectBaseDir,
            List<String> languagesToDownload) throws IOException {
        List<String> successfullyDownloadedLocalFilenames = new ArrayList<>();
        if (languagesToDownload == null || languagesToDownload.isEmpty()) {
            System.out.println(
                    "No languages specified via --supportedLanguages for OpenNLP sentence model download. Defaulting to 'en'.");
            languagesToDownload = Collections.singletonList("en");
        }

        List<String> normalizedLanguages = languagesToDownload.stream()
                .filter(lang -> lang != null && !lang.trim().isEmpty())
                .map(String::toLowerCase)
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());

        if (normalizedLanguages.isEmpty()) {
            System.out.println(
                    "Effectively no valid languages specified for OpenNLP model download after normalization. Skipping.");
            return successfullyDownloadedLocalFilenames;
        }

        System.out.println("Attempting to download OpenNLP sentence models for specified languages: "
                + String.join(", ", normalizedLanguages));

        Path resourcesDir = Paths.get(projectBaseDir.getAbsolutePath(), "src", "main", "resources");
        Path modelTargetDirInResources = resourcesDir.resolve(OPENNLP_MODEL_TARGET_DIR_IN_RESOURCES);

        if (!Files.exists(modelTargetDirInResources)) {
            Files.createDirectories(modelTargetDirInResources);
            System.out.println("Created OpenNLP model directory: " + modelTargetDirInResources.toAbsolutePath());
        }

        for (String langKey : normalizedLanguages) {
            String remoteModelFileName = LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME.get(langKey);
            String localModelFileName = LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.get(langKey);

            if (remoteModelFileName == null || localModelFileName == null) {
                System.err.println(
                        "OpenNLP sentence model configuration (remote or local filename) not found for language code: '"
                                + langKey + "'. Skipping this language.");
                System.err.println("Ensure language code '" + langKey
                        + "' is present in the predefined model maps in RagPomGenerator.java.");
                continue;
            }

            String modelUrlString = OPENNLP_MODEL_BASE_URL + remoteModelFileName;
            Path modelFile = modelTargetDirInResources.resolve(localModelFileName);

            if (Files.exists(modelFile)) {
                System.out.println("OpenNLP model " + localModelFileName + " for language '" + langKey
                        + "' already exists. Adding to list of available models.");
                successfullyDownloadedLocalFilenames.add(localModelFileName);
                continue;
            }

            System.out.println("Downloading OpenNLP sentence model " + localModelFileName + " for language '" + langKey
                    + "' from " + modelUrlString + " to " + modelFile.toAbsolutePath());
            URL url;
            try {
                url = new URL(modelUrlString);
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }

            try (InputStream in = url.openStream();
                    ReadableByteChannel rbc = Channels.newChannel(in);
                    FileOutputStream fos = new FileOutputStream(modelFile.toFile())) {
                fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
                System.out.println("Successfully downloaded " + localModelFileName);
                successfullyDownloadedLocalFilenames.add(localModelFileName);
            } catch (IOException e) {
                System.err.println("Failed to download OpenNLP model '" + localModelFileName + "' for language '"
                        + langKey + "': " + e.getMessage());
                if (Files.exists(modelFile)) {
                    try {
                        Files.delete(modelFile);
                    } catch (IOException ex) {
                        System.err.println("Also failed to delete partial model file: " + modelFile.toString() + " - "
                                + ex.getMessage());
                    }
                }
            }
        }
        return successfullyDownloadedLocalFilenames;
    }

    @Override
    public Void call() throws Exception {
        this.modelManager = new KompileModelManager();

        model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId(instanceGroupId);
        model.setArtifactId(instanceArtifactId);
        model.setVersion(instanceVersion);
        model.setPackaging("jar");

        Parent parentPom = new Parent();
        parentPom.setGroupId("org.springframework.boot");
        parentPom.setArtifactId("spring-boot-starter-parent");
        parentPom.setVersion(DEFAULT_SPRING_BOOT_VERSION);
        model.setParent(parentPom);
        model.getProperties().setProperty("javacpp.platform", javacppPlatform);

        File projectDir;
        if (outputFile.isDirectory()) {
            projectDir = outputFile;
        } else {
            projectDir = outputFile.getCanonicalFile().getParentFile();
            if (projectDir == null) {
                projectDir = new File(".").getCanonicalFile();
            }
        }
        if (!projectDir.exists() && !projectDir.mkdirs()) {
            throw new IOException("Could not create project directory: " + projectDir.getAbsolutePath());
        }

        if (includeChunkerSentence) {
            if (this.supportedLanguages == null || this.supportedLanguages.isEmpty()) {
                this.supportedLanguages = Collections.singletonList("en");
                System.out.println("No languages specified via --supportedLanguages for OpenNLP, defaulting to 'en'.");
            }
            List<String> normalizedLanguages = this.supportedLanguages.stream()
                    .filter(lang -> lang != null && !lang.trim().isEmpty())
                    .map(String::toLowerCase).map(String::trim).distinct()
                    .collect(Collectors.toList());

            for (String langKey : normalizedLanguages) {
                String remoteFileName = ModelConstants.getOpenNLPModelRemoteFilename(langKey);
                String localFileName = ModelConstants.getOpenNLPModelLocalFilename(langKey);

                if (remoteFileName == null || localFileName == null) {
                    System.err.println("OpenNLP sentence model configuration not found for language code: '" + langKey
                            + "'. Skipping.");
                    continue;
                }
                ModelDescriptor opennlpModelDesc = new ModelDescriptor(
                        "opennlp_sent_" + langKey,
                        ModelType.OPENNLP_SENTENCE,
                        ModelConstants.OPENNLP_MODEL_BASE_URL + remoteFileName,
                        Paths.get("opennlp", localFileName).toString(),
                        "1.2-2.5.0", null, Map.of("language", langKey));
                try {
                    Path modelPath = modelManager.ensureModelAvailable(opennlpModelDesc);
                    resolvedModelPaths.put(opennlpModelDesc.getModelId(), modelPath);
                    System.out.println(
                            "Ensured OpenNLP model for " + langKey + " is available at: " + modelPath.toAbsolutePath());
                } catch (IOException e) {
                    System.err.println(
                            "ERROR: Failed to ensure OpenNLP model for language '" + langKey + "': " + e.getMessage());
                }
            }
        }

        if (includeAnserini && this.anseriniIndexIds != null && !this.anseriniIndexIds.isEmpty()) {
            for (String indexIdInput : this.anseriniIndexIds) {
                String indexId = indexIdInput.trim();
                if (indexId.isEmpty())
                    continue;
                ModelDescriptor anseriniDesc = ModelConstants.getAnseriniIndexDescriptor(indexId);
                if (anseriniDesc == null) {
                    System.err.println("WARNING: Anserini index descriptor not found for ID: '" + indexId
                            + "'. Skipping. Consult ModelConstants.java.");
                    continue;
                }
                try {
                    Path indexPath = modelManager.ensureModelAvailable(anseriniDesc);
                    resolvedModelPaths.put(anseriniDesc.getModelId(), indexPath);
                    System.out.println(
                            "Ensured Anserini index '" + indexId + "' is available at: " + indexPath.toAbsolutePath());
                } catch (IOException e) {
                    System.err.println("ERROR: Failed to ensure Anserini index '" + indexId + "': " + e.getMessage());
                }
            }
        }

        if (includeAnserini && this.anseriniEncoderModelIds != null
                && !this.anseriniEncoderModelIds.isEmpty() || includeVectorStoreAnserini) {
            for (String encoderModelIdInput : this.anseriniEncoderModelIds) {
                String encoderModelId = encoderModelIdInput.trim();
                if (encoderModelId.isEmpty())
                    continue;
                ModelDescriptor encoderDesc = ModelConstants.getAnseriniEncoderModelDescriptor(encoderModelId);
                if (encoderDesc == null) {
                    System.err.println("WARNING: Anserini encoder model descriptor not found for ID: '" + encoderModelId
                            + "'. Skipping. Consult ModelConstants.java.");
                    continue;
                }
                try {
                    Path modelPath = modelManager.ensureModelAvailable(encoderDesc);
                    resolvedModelPaths.put(encoderDesc.getModelId(), modelPath);
                    System.out.println("Ensured Anserini encoder model '" + encoderModelId + "' is available at: "
                            + modelPath.toAbsolutePath());
                } catch (IOException e) {
                    System.err.println("ERROR: Failed to ensure Anserini encoder model '" + encoderModelId + "': "
                            + e.getMessage());
                }
            }
        }

        Properties props = new Properties();
        props.setProperty("java.version", "17");
        props.setProperty("start-class", CORE_APP_MAIN_CLASS_FQCN);
        props.setProperty("kompile.project.version", this.ragMcpVersion);
        props.setProperty("spring-boot.version", DEFAULT_SPRING_BOOT_VERSION);
        props.setProperty("spring-ai.version", DEFAULT_SPRING_AI_VERSION);
        props.setProperty("lombok.version", DEFAULT_LOMBOK_VERSION);
        props.setProperty("jackson.version", DEFAULT_JACKSON_VERSION);
        props.setProperty("guava.version", DEFAULT_GUAVA_VERSION);
        props.setProperty("log4j.version", DEFAULT_LOG4J_VERSION);
        props.setProperty("maven-compiler-plugin.version", DEFAULT_MAVEN_COMPILER_PLUGIN_VERSION);
        props.setProperty("maven-resources-plugin.version", DEFAULT_MAVEN_RESOURCES_PLUGIN_VERSION);
        props.setProperty("maven-jar-plugin.version", DEFAULT_MAVEN_JAR_PLUGIN_VERSION);
        props.setProperty("postgres.version", DEFAULT_POSTGRES_VERSION);
        props.setProperty("native-maven-plugin.version", DEFAULT_NATIVE_MAVEN_PLUGIN_VERSION);
        props.setProperty("build-helper-maven-plugin.version", DEFAULT_BUILD_HELPER_MAVEN_PLUGIN_VERSION);
        props.setProperty("native.image.name", this.instanceArtifactId + "-native");
        props.setProperty("embedded-postgres.version", DEFAULT_EMBEDDED_POSTGRES_VERSION);

        String defaultRuntimeLangForOpenNLP = "en";
        if (this.supportedLanguages != null && !this.supportedLanguages.isEmpty()) {
            String firstSpecifiedLang = this.supportedLanguages.get(0).toLowerCase().trim();
            if (ModelConstants.isOpenNLPLanguageSupported(firstSpecifiedLang)) {
                defaultRuntimeLangForOpenNLP = firstSpecifiedLang;
            } else {
                System.out.println("Warning: First language '" + this.supportedLanguages.get(0) +
                        "' from --supportedLanguages is not in the known model list for setting runtime default property 'kompile.opennlp.sentence.language'. "
                        +
                        "Defaulting property to 'en'.");
            }
        }
        props.setProperty("kompile.opennlp.sentence.language", defaultRuntimeLangForOpenNLP);
        props.setProperty("instanceArtifactId", this.instanceArtifactId);
        props.setProperty("backend", this.backend);

        model.setProperties(props);

        addApplicationDependencies();
        addBackendDependencies();
        addApplicationBuild();

        // Always add cpu profile so -Pcpu switches to nd4j-native
        addCpuProfile();

        NativeImageSupportWriter nativeImageSupportWriter = new NativeImageSupportWriter();
        if (buildNative) {
            addNativeProfile(CORE_APP_MAIN_CLASS_FQCN, Collections.emptyList());
            nativeImageSupportWriter.writeCglibPatchScript(projectDir);
            nativeImageSupportWriter.writeSpringProperties(projectDir);
        }

        addSpringRepositories();

        MavenXpp3Writer mavenXpp3Writer = new MavenXpp3Writer();
        File finalPomFile = outputFile.isDirectory() ? new File(outputFile, "pom-rag-instance.xml") : outputFile;

        try (FileWriter fileWriter = new FileWriter(finalPomFile)) {
            mavenXpp3Writer.write(fileWriter, model);
            System.out.println("Successfully generated RAG application POM: " + finalPomFile.getAbsolutePath());
        }

        new ApplicationPropertiesWriter(
                instanceArtifactId, instanceGroupId, appTitle,
                databaseUrl, databaseUsername, databasePassword,
                enableSchemaInit,
                includeVectorstorePgvector, includeEmbeddingPostgresml, includePgmlIndexer,
                includeEmbeddingOpenai, includeEmbeddingSentenceTransformer,
                includeLlmOpenai, includeLlmAnthropic, includeLlmGemini,
                includeVectorstoreChroma, includeAnserini, includeChunkerSentence,
                supportedLanguages, anseriniIndexIds, anseriniEncoderModelIds,
                resolvedModelPaths, modelManager
        ).generateApplicationPropertiesFile(projectDir, props);

        PostgresDdlGenerator postgresDdlGenerator = new PostgresDdlGenerator(
                includeVectorstorePgvector, includeEmbeddingPostgresml, includePgmlIndexer, enableSchemaInit);

        if (includeEmbeddingPostgresml || includePgmlIndexer) {
            postgresDdlGenerator.generatePgmlSchemaFiles(projectDir);
        }
        if (enableSchemaInit && (includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer)) {
            postgresDdlGenerator.generateSqlSchemaFiles(projectDir);
        }
        if (includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer) {
            ProjectSourceGenerator sourceGenerator = new ProjectSourceGenerator(
                    instanceArtifactId, instanceGroupId,
                    includeEmbeddingPostgresml, includePgmlIndexer, includeVectorstorePgvector);
            sourceGenerator.generateDatabaseConfiguration(projectDir);
            sourceGenerator.generateGlobalExceptionHandler(projectDir);
            sourceGenerator.generateProviderConfigurationClass(projectDir);
        }

        return null;
    }


    private void addApplicationDependencies() {
        defaultDependencies.clear();
        // ByteBuddy - required at runtime by Hibernate 6.x bytecode enhancement.
        // Version managed by spring-boot-starter-parent BOM property.
        addDependency(defaultDependencies, "net.bytebuddy", "byte-buddy", "${byte-buddy.version}");
        addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter-web",
                "${spring-boot.version}");
        addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter", "${spring-boot.version}");
        // Jakarta Bean Validation provider (Hibernate Validator) - required by modules
        // using jakarta.validation-api
        addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter-validation",
                "${spring-boot.version}");
        // Quartz scheduler - required by kompile-kclaw (KClawAutoConfiguration)
        addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter-quartz",
                "${spring-boot.version}");
        addDependency(defaultDependencies, "org.springframework.ai", "spring-ai-starter-mcp-client",
                "${spring-ai.version}");
        addDependency(defaultDependencies, "org.springframework.ai", "spring-ai-starter-mcp-server-webmvc",
                "${spring-ai.version}");
        addDependency(defaultDependencies, "org.eclipse.deeplearning4j", "tokenizers-native", "${nd4j.version}", "compile",
                javacppPlatform, false);

        addDependency(defaultDependencies, "jakarta.mail", "jakarta.mail-api", DEFAULT_JAKARTA_MAIL_VERSION);

        addDependency(defaultDependencies, "org.apache.logging.log4j", "log4j-api", "${log4j.version}");
        addDependency(defaultDependencies, "org.apache.logging.log4j", "log4j-core", "${log4j.version}");

        if (includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer) {
            addDependency(defaultDependencies, "org.postgresql", "postgresql", "${postgres.version}", "compile", null,
                    false);
            addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter-jdbc",
                    "${spring-boot.version}");
        }

        if (includeAppMain)
            addDependency(defaultDependencies, "ai.kompile", "kompile-app-main", "${kompile.project.version}");
        if (includeAppCore)
            addDependency(defaultDependencies, "ai.kompile", "kompile-app-core", "${kompile.project.version}");
        if (includeLoadersOrchestrator)
            addDependency(defaultDependencies, "ai.kompile", "kompile-app-loaders-orchestrator",
                    "${kompile.project.version}");

        if (includeLoaderTika)
            addDependency(defaultDependencies, "ai.kompile", "kompile-loader-tika", "${kompile.project.version}");
        if (includeLoaderPdf)
            addDependency(defaultDependencies, "ai.kompile", "kompile-loader-pdf-extended", "${kompile.project.version}");
        if (includeLoaderMicrosoft)
            addDependency(defaultDependencies, "ai.kompile", "kompile-loader-microsoft", "${kompile.project.version}");
        if (includeLoaderMail)
            addDependency(defaultDependencies, "ai.kompile", "kompile-loader-mail", "${kompile.project.version}");
        if (includeLoaderPdfExtended)
            addDependency(defaultDependencies, "ai.kompile", "kompile-loader-pdf-extended",
                    "${kompile.project.version}");

        if (includeChunkerSentence)
            addDependency(defaultDependencies, "ai.kompile", "kompile-chunker-sentence", "${kompile.project.version}");
        if (includeChunkerRecursiveCharacter)
            addDependency(defaultDependencies, "ai.kompile", "kompile-chunker-recursivecharacter",
                    "${kompile.project.version}");
        if (includeChunkerMarkdown)
            addDependency(defaultDependencies, "ai.kompile", "kompile-chunker-markdown", "${kompile.project.version}");
        if (includeChunkerToken)
            addDependency(defaultDependencies, "ai.kompile", "kompile-chunker-token", "${kompile.project.version}");

        if (includeAnserini)
            addDependency(defaultDependencies, "ai.kompile", "kompile-app-anserini", "${kompile.project.version}");
        if (includeEmbeddingAnserini)
            addDependency(defaultDependencies, "ai.kompile", "kompile-embedding-anserini",
                    "${kompile.project.version}");
        if (includeVectorStoreAnserini) {
            addDependency(defaultDependencies, "ai.kompile", "kompile-vectorstore-anserini",
                    "${kompile.project.version}");
        }

        if (includeLlmOpenai)
            addDependency(defaultDependencies, "ai.kompile", "kompile-app-openai-llm", "${kompile.project.version}");
        if (includeLlmAnthropic)
            addDependency(defaultDependencies, "ai.kompile", "kompile-app-anthropic-llm", "${kompile.project.version}");
        if (includeLlmGemini)
            addDependency(defaultDependencies, "ai.kompile", "kompile-app-gemini-llm", "${kompile.project.version}");
        if (includeEmbeddingOpenai)
            addDependency(defaultDependencies, "ai.kompile", "kompile-embedding-openai", "${kompile.project.version}");
        if (includeEmbeddingSentenceTransformer)
            addDependency(defaultDependencies, "ai.kompile", "kompile-embedding-sentence-transformer",
                    "${kompile.project.version}");
        if (includeVectorstoreChroma)
            addDependency(defaultDependencies, "ai.kompile", "kompile-vectorstore-chroma",
                    "${kompile.project.version}");
        if (includeEmbeddingPostgresml)
            addDependency(defaultDependencies, "ai.kompile", "kompile-embedding-postgresml",
                    "${kompile.project.version}");
        if (includePgmlIndexer)
            addDependency(defaultDependencies, "ai.kompile", "kompile-app-pgml-indexer", "${kompile.project.version}");
        if (includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer) {
            addDependency(defaultDependencies, "ai.kompile", "kompile-vectorstore-pgvector",
                    "${kompile.project.version}");
        }
        if (includeToolFilesystem)
            addDependency(defaultDependencies, "ai.kompile", "kompile-tool-filesystem", "${kompile.project.version}");
        if (includeToolRag)
            addDependency(defaultDependencies, "ai.kompile", "kompile-tool-rag", "${kompile.project.version}");
        if (includeToolTableSearch)
            addDependency(defaultDependencies, "ai.kompile", "kompile-tool-table-search", "${kompile.project.version}");
        if (includeKvCache)
            addDependency(defaultDependencies, "ai.kompile", "kompile-kvcache", "${kompile.project.version}");
        if (includeRagPipeline)
            addDependency(defaultDependencies, "ai.kompile", "kompile-rag-pipeline", "${kompile.project.version}");
        if (includeGraphAlgorithms)
            addDependency(defaultDependencies, "ai.kompile", "kompile-graph-algorithms", "${kompile.project.version}");
        if (includeKnowledgeGraph)
            addDependency(defaultDependencies, "ai.kompile", "kompile-knowledge-graph", "${kompile.project.version}");
        if (includeOcr) {
            addDependency(defaultDependencies, "ai.kompile", "kompile-ocr-core", "${kompile.project.version}");
            addDependency(defaultDependencies, "ai.kompile", "kompile-ocr-models", "${kompile.project.version}");
            addDependency(defaultDependencies, "ai.kompile", "kompile-ocr-postprocess", "${kompile.project.version}");
            addDependency(defaultDependencies, "ai.kompile", "kompile-ocr-integration", "${kompile.project.version}");
            addDependency(defaultDependencies, "ai.kompile", "kompile-ocr-datapipeline", "${kompile.project.version}");
        }
        if (includeCrawlGraph)
            addDependency(defaultDependencies, "ai.kompile", "kompile-crawl-graph", "${kompile.project.version}");
        if (includeCrawlerCore)
            addDependency(defaultDependencies, "ai.kompile", "kompile-crawler-core", "${kompile.project.version}");
        if (includeEventAttribution)
            addDependency(defaultDependencies, "ai.kompile", "kompile-event-attribution", "${kompile.project.version}");
        if (includeProcessEngine)
            addDependency(defaultDependencies, "ai.kompile", "kompile-process-engine", "${kompile.project.version}");
        if (includeProcessDiscovery)
            addDependency(defaultDependencies, "ai.kompile", "kompile-process-discovery", "${kompile.project.version}");
        if (includeCodeIndexer)
            addDependency(defaultDependencies, "ai.kompile", "kompile-code-indexer", "${kompile.project.version}");
        if (includeDataEnrichment)
            addDependency(defaultDependencies, "ai.kompile", "kompile-data-enrichment", "${kompile.project.version}");

        addDependency(defaultDependencies, "org.projectlombok", "lombok", "${lombok.version}", "provided", null, true);
        addDependency(defaultDependencies, "com.fasterxml.jackson.core", "jackson-databind", "${jackson.version}");
        addDependency(defaultDependencies, "com.google.guava", "guava", "${guava.version}");
        model.setDependencies(defaultDependencies);
    }

    private void addApplicationBuild() {
        if (model.getBuild() == null) {
            model.setBuild(new Build());
        }

        Build build = model.getBuild();

        try {
            Plugin compilerPlugin = createPlugin("org.apache.maven.plugins", "maven-compiler-plugin",
                    "${maven-compiler-plugin.version}");
            Xpp3Dom compilerConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "  <release>${java.version}</release>\n" +
                            "<parameters>true</parameters>\n" +
                            "  <annotationProcessorPaths>" +
                            "    <path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>${lombok.version}</version></path>"
                            +
                            "    <path><groupId>org.springframework.boot</groupId><artifactId>spring-boot-configuration-processor</artifactId><version>${spring-boot.version}</version></path>"
                            +
                            "  </annotationProcessorPaths>" +
                            "</configuration>"));
            compilerPlugin.setConfiguration(compilerConfig);
            build.addPlugin(compilerPlugin);

            Plugin resourcesPlugin = createPlugin("org.apache.maven.plugins", "maven-resources-plugin",
                    "${maven-resources-plugin.version}");
            build.addPlugin(resourcesPlugin);

            Plugin jarPlugin = createPlugin("org.apache.maven.plugins", "maven-jar-plugin",
                    "${maven-jar-plugin.version}");
            Xpp3Dom jarConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "  <archive><manifest><mainClass>${start-class}</mainClass><addClasspath>true</addClasspath><classpathPrefix>BOOT-INF/lib/</classpathPrefix></manifest></archive>"
                            +
                            "</configuration>"));
            jarPlugin.setConfiguration(jarConfig);
            build.addPlugin(jarPlugin);

            Plugin springBootMainBuildPlugin = createPlugin("org.springframework.boot", "spring-boot-maven-plugin",
                    "${spring-boot.version}");
            Xpp3Dom springBootMainConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "  <mainClass>${start-class}</mainClass>" +
                            "  <excludes><exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude></excludes>"
                            +
                            "</configuration>"));
            springBootMainBuildPlugin.setConfiguration(springBootMainConfig);
            PluginExecution springBootRepackageMain = new PluginExecution();
            springBootRepackageMain.setId("repackage");
            springBootRepackageMain.addGoal("repackage");
            springBootMainBuildPlugin.addExecution(springBootRepackageMain);
            build.addPlugin(springBootMainBuildPlugin);

        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Error configuring build plugins", e);
        }
    }

    /**
     * Add the ND4J backend dependency (base + platform-classified) using the ${backend} property.
     * This allows -Pcpu to swap the entire backend by overriding the property.
     */
    private void addBackendDependencies() {
        addDependency(defaultDependencies, "org.eclipse.deeplearning4j", "${backend}",
                "1.0.0-SNAPSHOT");
        addDependency(defaultDependencies, "org.eclipse.deeplearning4j", "${backend}",
                "1.0.0-SNAPSHOT", "compile", javacppPlatform, false);
    }

    /**
     * Add a 'cpu' profile that overrides the backend property to nd4j-native.
     * Usage: mvn clean package -Pcpu
     */
    private void addCpuProfile() {
        Profile cpuProfile = new Profile();
        cpuProfile.setId("cpu");
        Properties cpuProps = new Properties();
        cpuProps.setProperty("backend", "nd4j-native");
        cpuProfile.setProperties(cpuProps);
        model.addProfile(cpuProfile);
    }

    private void addNativeProfile(String nativeImageMainClassFqcn, List<String> modelFilesToIncludePreviously) {
        Profile nativeProfile = new Profile();
        nativeProfile.setId("native");
        Build nativeProfileBuild = new Build();

        Plugin springBootPluginNative = createPlugin("org.springframework.boot", "spring-boot-maven-plugin",
                "${spring-boot.version}");
        try {
            Xpp3Dom springBootNativeConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "  <mainClass>"
                            + (nativeImageMainClassFqcn != null ? nativeImageMainClassFqcn : CORE_APP_MAIN_CLASS_FQCN)
                            + "</mainClass>" +
                            "  <classifier>exec</classifier>" +
                            "  <jvmArguments>-Djava.awt.headless=true -Dorg.apache.lucene.store.MMapDirectory.enableMemorySegments=false</jvmArguments>" +
                            "  <excludes><exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude></excludes>"
                            +
                            "</configuration>"));
            springBootPluginNative.setConfiguration(springBootNativeConfig);
        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Error configuring spring-boot-maven-plugin in native profile", e);
        }

        PluginExecution processAotExecution = new PluginExecution();
        processAotExecution.setId("process-aot");
        processAotExecution.addGoal("process-aot");
        springBootPluginNative.addExecution(processAotExecution);

        PluginExecution repackageExecutionNative = new PluginExecution();
        repackageExecutionNative.setId("repackage-native-profile");
        repackageExecutionNative.addGoal("repackage");
        springBootPluginNative.addExecution(repackageExecutionNative);
        nativeProfileBuild.addPlugin(springBootPluginNative);

        Plugin buildHelperPlugin = createPlugin("org.codehaus.mojo", "build-helper-maven-plugin",
                "${build-helper-maven-plugin.version}");
        PluginExecution addAotSourcesExecution = new PluginExecution();
        addAotSourcesExecution.setId("add-spring-aot-sources");
        addAotSourcesExecution.addGoal("add-source");
        addAotSourcesExecution.setPhase("generate-sources");
        try {
            Xpp3Dom addAotSourcesConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration><sources><source>${project.build.directory}/spring-aot/main/sources</source></sources></configuration>"));
            addAotSourcesExecution.setConfiguration(addAotSourcesConfig);
        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Error configuring build-helper-maven-plugin for AOT sources", e);
        }
        buildHelperPlugin.addExecution(addAotSourcesExecution);

        PluginExecution addAotResourcesExecution = new PluginExecution();
        addAotResourcesExecution.setId("add-spring-aot-resources");
        addAotResourcesExecution.addGoal("add-resource");
        addAotResourcesExecution.setPhase("generate-resources");
        try {
            Xpp3Dom addAotResourcesConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration><resources><resource><directory>${project.build.directory}/spring-aot/main/resources</directory></resource></resources></configuration>"));
            addAotResourcesExecution.setConfiguration(addAotResourcesConfig);
        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Error configuring build-helper-maven-plugin for AOT resources", e);
        }
        buildHelperPlugin.addExecution(addAotResourcesExecution);
        nativeProfileBuild.addPlugin(buildHelperPlugin);

        // maven-antrun-plugin: post-process AOT reflect-config to add unsafeAllocated for CGLIB proxies
        Plugin antrunPlugin = createPlugin("org.apache.maven.plugins", "maven-antrun-plugin", "3.1.0");
        PluginExecution patchCglibExecution = new PluginExecution();
        patchCglibExecution.setId("patch-cglib-unsafe-allocated");
        patchCglibExecution.setPhase("prepare-package");
        patchCglibExecution.addGoal("run");
        try {
            Xpp3Dom antrunConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration><target>" +
                            "<exec executable=\"python3\" failonerror=\"true\">" +
                            "<arg value=\"${project.basedir}/src/main/resources/scripts/patch-cglib-unsafe.py\"/>" +
                            "<arg value=\"${project.build.directory}\"/>" +
                            "</exec>" +
                            "</target></configuration>"));
            patchCglibExecution.setConfiguration(antrunConfig);
        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Error configuring maven-antrun-plugin for CGLIB patch", e);
        }
        antrunPlugin.addExecution(patchCglibExecution);
        nativeProfileBuild.addPlugin(antrunPlugin);

        Plugin nativeMavenPlugin = createPlugin("org.graalvm.buildtools", "native-maven-plugin",
                "${native-maven-plugin.version}");
        nativeMavenPlugin.setExtensions(true);

        Xpp3Dom nativePluginConfig = new Xpp3Dom("configuration");
        addChild(nativePluginConfig, "imageName", "${native.image.name}");
        addChild(nativePluginConfig, "mainClass",
                (nativeImageMainClassFqcn != null ? nativeImageMainClassFqcn : CORE_APP_MAIN_CLASS_FQCN));
        addChild(nativePluginConfig, "quickBuild", "false");
        addChild(nativePluginConfig, "jarArtifact", "${project.build.directory}/${project.build.finalName}-exec.jar");

        Xpp3Dom metadataRepoElement = addChild(nativePluginConfig, "metadataRepository", null);
        addChild(metadataRepoElement, "enabled", "true");

        Xpp3Dom buildArgsDom = new Xpp3Dom("buildArgs");

        addBuildArg(buildArgsDom, "-J-Xmx16g");
        addBuildArg(buildArgsDom, "--verbose");
        addBuildArg(buildArgsDom, "--no-fallback");
        addBuildArg(buildArgsDom, "--allow-incomplete-classpath");
        addBuildArg(buildArgsDom, "-H:+ReportExceptionStackTraces");
        addBuildArg(buildArgsDom, "-Dspring.native.remove-unused-autoconfig=true");
        addBuildArg(buildArgsDom, "-H:+AddAllFileSystemProviders");
        addBuildArg(buildArgsDom, "--enable-url-protocols=http,https");
        addBuildArg(buildArgsDom, "-Djava.awt.headless=true");
        addBuildArg(buildArgsDom, "-H:+UnlockExperimentalVMOptions");
        addBuildArg(buildArgsDom, "-H:+AllowDeprecatedBuilderClassesOnImageClasspath");
        addBuildArg(buildArgsDom, "-H:+ReportUnsupportedElementsAtRuntime");

        addBuildArg(buildArgsDom, "--initialize-at-build-time=org.nd4j.shade.protobuf.UnsafeUtil");
        addBuildArg(buildArgsDom, "--initialize-at-build-time=com.google.protobuf.UnsafeUtil");

        addBuildArg(buildArgsDom, "-H:+AddAllFileSystemProviders");
        addBuildArg(buildArgsDom, "-H:+EnableAllSecurityServices");
        addBuildArg(buildArgsDom, "--enable-all-security-services");

        addBuildArg(buildArgsDom,
                "--initialize-at-build-time=java.rmi.server.Operation,org.apache.logging.log4j.Util,org.apache.logging.log4j.status.StatusLogger,org.apache.logging.log4j.util.ProviderUtil,org.apache.logging.log4j.util.PropertySource$Util,org.apache.logging.log4j.core.impl.Log4jProvider,org.apache.logging.log4j.spi.AbstractLogger,org.apache.logging.log4j.core.impl.Log4jContextFactory,org.apache.logging.log4j.core.selector.ClassLoaderContextSelector,org.apache.logging.log4j.core.LifeCycle$State,org.apache.logging.log4j.status.StatusLogger,org.apache.logging.log4j.spi.StandardLevel,,org.apache.logging.log4j.util.Strings,org.apache.logging.log4j.Level,org.apache.logging.log4j.util.PropertiesUtil,org.apache.logging.log4j.util.OsgiServiceLocator,org.apache.logging.log4j.util.PropertyFilePropertySource,org.apache.logging.log4j.message.ParameterFormatter,org.apache.logging.log4j.status.StatusLogger$Config,org.apache.logging.log4j.status.StatusLogger$InstanceHolder");
        addBuildArg(buildArgsDom,
                "--initialize-at-run-time=ai.kompile.app.MainApplication,org.nd4j.linalg.cpu.nativecpu.NDArray,org.nd4j.linalg.api.memory.deallocation.DeallocatorService$DeallocatorServiceThread,org.nd4j.linalg.api.ops.impl.scalar.LeakyReLU,org.nd4j.linalg.cpu.nativecpu.CpuNDArrayFactory,org.nd4j.jita.constant.ProtectedCachedShapeInfoProvider,org.nd4j.jita.constant.ConstantProtector,org.nd4j.imports.converters.DifferentialFunctionClassHolder,org.nd4j.linalg.api.memory.deallocation.DeallocatorService,org.nd4j.linalg.factory.Nd4j,org.eclipse.deeplearning4j.tokenizers.presets.TokenizersHelper,org.eclipse.deeplearning4j.tokenizers.bindings.TokenizersNative,org.nd4j.autodiff.samediff,org.nd4j.imports.converters.DifferentialFunctionClassHolder,org.nd4j.linalg.api.ops,org.bytedeco.javacpp.indexer,org.nd4j.nativeblas.NativeOpsHolder,org.apache.tomcat.util.compat,org.apache.catalina.webresources.DirResourceSet,org.bytedeco.javacpp.Loader,org.bytedeco.javacpp.tools.PointerBufferPoolMXBean,org.nd4j.linalg.factory.Nd4j,org.nd4j.linalg.cpu.nativecpu.CpuBackend,org.nd4j.linalg.learning.config,org.nd4j.linalg.cpu.nativecpu.CpuEnvironment,org.nd4j.linalg.cpu.nativecpu.buffer.CpuDeallocator,org.nd4j.linalg.cpu.nativecpu.bindings.Nd4jCpu$Environment,org.bytedeco.javacpp.Pointer,org.nd4j.linalg.cpu.nativecpu.buffer.CpuDeallocator,org.nd4j.linalg.api.memory.deallocation.DeallocatorService$DeallocatorServiceThread,org.apache.lucene.util.ScalarQuantizer,org.jline.nativ.JLineLibrary,org.jline.terminal.impl.jna,org.jline.terminal.impl.jna.linux.LinuxNativePty$UtilLibrary,org.eclipse.deeplearning4j.nativeblas.NativeOpsHolder,org.eclipse.deeplearning4j.linalg.api.memory.deallocation.DeallocatorService$DeallocatorServiceThread,org.eclipse.deeplearning4j.linalg.cpu.nativecpu.CpuEnvironment,org.bytedeco.onnxruntime.presets.onnxruntime,org.bytedeco.openblas.presets.openblas,org.bytedeco.onnx.presets.onnx,org.bytedeco.opencl.presets.OpenCL,org.bytedeco.openblas.presets.openblas_nolapack,org.bytedeco.dnnl.presets.dnnl,org.bytedeco.mkldnn.global.mklml,org.bytedeco.mkldnn.presets.mklml,org.bytedeco.opencl.global.OpenCL,org.eclipse.deeplearning4j.linalg.cpu.nativecpu.bindings.Nd4jCpu,org.bytedeco.onnx.global.onnx,org.bytedeco.tensorflow.presets.tensorflow,org.bytedeco.openblas.global.openblas,org.bytedeco.mkldnn.global.mkldnn,org.bytedeco.openblas.global.openblas_nolapack,org.bytedeco.onnxruntime.global.onnxruntime,org.bytedeco.javacpp.Loader$Helper,org.bytedeco.javacpp.Loader,org.bytedeco.dnnl.global.dnnl,org.bytedeco.javacpp.Pointer,org.eclipse.deeplearning4j.autodiff.samediff.internal.memory.ArrayCacheMemoryMgr,org.eclipse.deeplearning4j.linalg.factory.Nd4j,org.bytedeco.javacpp.Pointer$DeallocatorThread,org.eclipse.deeplearning4j.linalg.api.ops.impl.layers.ExternalErrorsFunction,org.springframework.ai.chat.client.advisor,reactor.core.scheduler,java.awt.event,org.apache.poi.util.RandomSingleton,sun.awt.X11,sun.rmi.server,java.rmi.server,sun.java.rmi.server,sun.rmi.transport,org.apache.tomcat.jni.SSL,sun.awt.X11GraphicsConfig,org.springframework.web.reactive.function.client.DefaultExchangeStrategiesBuilder,org.springframework.boot.loader.ref.DefaultCleaner,org.apache.tomcat.util.net.openssl.OpenSSLContext,org.apache.tomcat.util.net.openssl.OpenSSLEngine,sun.awt.dnd.SunDropTargetContextPeer$EventDispatcher,org.springframework.core.io.VfsUtils,org.springframework.boot.loader.ref.Cleaner,org.springframework.boot.loader.ref.DefaultCleaner,org.springframework.web.reactive.function.client.DefaultExchangeStrategiesBuilder,reactor.core.scheduler.SchedulerState$DisposeAwaiterRunnable,org.apache.catalina.mbeans.MBeanUtils,org.apache.catalina.mbeans.MBeanFactory");
        addBuildArg(buildArgsDom,
                "--trace-class-initialization=org.apache.tomcat.util.compat.Jre12Compat,java.lang.ref.WeakReference,java.lang.ref.SoftReference,org.nd4j.nativeblas.NativeOpsHolder,org.apache.tomcat.util.compat,org.apache.catalina.webresources.DirResourceSet,org.bytedeco.javacpp.Loader,org.bytedeco.javacpp.tools.PointerBufferPoolMXBean,java.rmi.server.Operation,org.nd4j.linalg.factory.Nd4j,org.nd4j.linalg.cpu.nativecpu.CpuBackend,org.nd4j.linalg.learning.config,org.nd4j.linalg.cpu.nativecpu.buffer.CpuDeallocator,org.nd4j.linalg.cpu.nativecpu.CpuEnvironment,org.nd4j.linalg.cpu.nativecpu.bindings.Nd4jCpu$Environment,org.nd4j.linalg.cpu.nativecpu.buffer.CpuDeallocator,org.bytedeco.javacpp.Pointer,org.nd4j.linalg.api.memory.deallocation.DeallocatorService$DeallocatorServiceThread,sun.nio.ch.FileChannelImpl,org.apache.lucene.util.ScalarQuantizer,org.jline.terminal.impl.jna,org.jline.terminal.impl.jna.linux.LinuxNativePty$UtilLibrary,org.jline.nativ.JLineLibrary,org.eclipse.deeplearning4j.nativeblas.NativeOpsHolder,org.eclipse.deeplearning4j.linalg.api.memory.deallocation.DeallocatorService$DeallocatorServiceThread,org.eclipse.deeplearning4j.linalg.cpu.nativecpu.CpuEnvironment,org.bytedeco.openblas.presets.openblas,org.bytedeco.onnxruntime.presets.onnxruntime,org.bytedeco.onnx.presets.onnx,org.bytedeco.opencl.presets.OpenCL,org.bytedeco.openblas.presets.openblas_nolapack,org.bytedeco.dnnl.presets.dnnl,org.bytedeco.mkldnn.presets.mklml,org.bytedeco.opencl.global.OpenCL,org.bytedeco.tensorflow.presets.tensorflow,org.bytedeco.mkldnn.global.mklml,org.eclipse.deeplearning4j.linalg.cpu.nativecpu.bindings.Nd4jCpu,org.bytedeco.onnx.global.onnx,org.bytedeco.mkldnn.global.mkldnn,org.bytedeco.openblas.global.openblas,org.bytedeco.openblas.global.openblas_nolapack,org.bytedeco.onnxruntime.global.onnxruntime,org.bytedeco.javacpp.Loader$Helper,org.bytedeco.javacpp.Loader,org.bytedeco.dnnl.global.dnnl,org.bytedeco.javacpp.Pointer,org.eclipse.deeplearning4j.autodiff.samediff.internal.memory.ArrayCacheMemoryMgr,org.bytedeco.javacpp.Pointer$DeallocatorThread,org.eclipse.deeplearning4j.linalg.api.ops.impl.layers.ExternalErrorsFunction,org.eclipse.deeplearning4j.linalg.factory.Nd4j,org.springframework.ai.chat.client.advisor.api.BaseAdvisor,reactor.core.scheduler.Schedulers,reactor.core.scheduler.BoundedElasticScheduler$BoundedState,reactor.core.scheduler.BoundedElasticSchedulerSupplier,reactor.core.scheduler.BoundedElasticScheduler,reactor.core.scheduler.BoundedElasticScheduler$BoundedServices$1,reactor.core.scheduler.BoundedElasticScheduler$BoundedServices");

        addBuildArg(buildArgsDom, "-H:IncludeResources=log4j2.xml");
        addBuildArg(buildArgsDom, "-H:IncludeResources=log4j2-spring.xml");
        addBuildArg(buildArgsDom, "-H:IncludeResources=log4j2.component.properties");
        addBuildArg(buildArgsDom, "-H:IncludeResources=.*Log4j2Plugins.dat$");
        addBuildArg(buildArgsDom, "-H:IncludeResources=META-INF/services/org.apache.logging.log4j.spi.Provider");
        addBuildArg(buildArgsDom, "-H:+AllowDeprecatedBuilderClassesOnImageClasspath");
        addBuildArg(buildArgsDom, "-H:IncludeResources=META-INF/native-image/.*\\.json");
        addBuildArg(buildArgsDom, "-H:IncludeResources=META-INF/services/.*");

        addBuildArg(buildArgsDom, "-H:IncludeResources=org/eclipse/deeplearning4j/tokenizers/.*\\.dll");
        addBuildArg(buildArgsDom, "-H:IncludeResources=org/eclipse/deeplearning4j/tokenizers/.*\\.dylib");
        addBuildArg(buildArgsDom, "-H:IncludeResources=org/eclipse/deeplearning4j/tokenizers/.*\\.so");

        addBuildArg(buildArgsDom, "-H:IncludeResources=ai/kompile/bindings/.*\\.so");
        addBuildArg(buildArgsDom, "-H:IncludeResources=ai/kompile/bindings/.*\\.dll");
        addBuildArg(buildArgsDom, "-H:IncludeResources=ai/kompile/bindings/.*\\.dylib");

        // Exclude native libs from image (side-loaded at runtime) to keep image under 2GB
        addBuildArg(buildArgsDom, "-H:ExcludeResources=org/bytedeco/.*\\.so$");
        addBuildArg(buildArgsDom, "-H:ExcludeResources=org/bytedeco/.*\\.so\\..*");
        addBuildArg(buildArgsDom, "-H:ExcludeResources=org/bytedeco/.*\\.dll$");
        addBuildArg(buildArgsDom, "-H:ExcludeResources=org/bytedeco/.*\\.dylib$");
        addBuildArg(buildArgsDom, "-H:ExcludeResources=org/nd4j/.*\\.so$");
        addBuildArg(buildArgsDom, "-H:ExcludeResources=org/nd4j/.*\\.so\\..*");
        addBuildArg(buildArgsDom, "-H:ExcludeResources=org/nd4j/.*\\.dll$");
        addBuildArg(buildArgsDom, "-H:ExcludeResources=org/nd4j/.*\\.dylib$");
        addBuildArg(buildArgsDom, "-H:ExcludeResources=org/eclipse/deeplearning4j/tokenizers/.*/lib.*\\.so.*");
        addBuildArg(buildArgsDom, "-H:ExcludeResources=org/eclipse/deeplearning4j/tokenizers/.*/libjni.*\\.so");
        addBuildArg(buildArgsDom, "-H:ExcludeResources=linux-x86_64/.*\\.so$");
        addBuildArg(buildArgsDom, "-H:ExcludeResources=linux-x86_64/.*\\.so\\..*");
        addBuildArg(buildArgsDom, "-H:ExcludeResources=linux-aarch64/.*");
        addBuildArg(buildArgsDom, "-H:ExcludeResources=windows-x86_64/.*");
        addBuildArg(buildArgsDom, "-H:ExcludeResources=macosx-.*/.*");
        // Hibernate: exclude BytecodeProvider service file so ServiceLoader returns empty,
        // triggering Hibernate's built-in fallback to none.BytecodeProviderImpl
        addBuildArg(buildArgsDom, "-H:ExcludeResources=META-INF/services/org.hibernate.bytecode.spi.BytecodeProvider");

        addBuildArg(buildArgsDom, "-H:IncludeResources=ai/kompile/.*\\.schema\\.json");
        addBuildArg(buildArgsDom, "-H:IncludeResources=META-INF/spring/.*\\.imports");
        addBuildArg(buildArgsDom, "-H:IncludeResources=META-INF/spring\\.components");
        addBuildArg(buildArgsDom, "-H:DeadlockWatchdogInterval=30");
        addBuildArg(buildArgsDom, "-H:+DeadlockWatchdogExitOnTimeout");
        addBuildArg(buildArgsDom, "-H:IncludeResources=org/apache/pdfbox/resources/afm/.*");
        addBuildArg(buildArgsDom,
                "--trace-object-instantiation=org.eclipse.deeplearning4j.linalg.api.memory.deallocation.DeallocatorService$DeallocatorServiceThread");
        addBuildArg(buildArgsDom, "-H:+ReportUnsupportedElementsAtRuntime");
        addBuildArg(buildArgsDom, "-H:+AllowVMInspection");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.bytedeco.javacpp.Pointer$NativeDeallocator");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.bytedeco.javacpp.PointerScope");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=io.methvin.watchservice.jna.CarbonAPI");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.eclipse.deeplearning4j.llm.tokenizer.HuggingFaceTokenizer");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.eclipse.deeplearning4j.tokenizers.bindings.TokenizersNative");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.bytedeco.javacpp.tools.Logger");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=sun.awt");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=sun.awt.dnd");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=sun.java2d.Disposer");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=sun.font");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=sun.java2d.opengl");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=javax.imageio");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.h2.store.fs.niomem.FileNioMemData");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.apache.juli");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.springframework.util.AlternativeJdkIdGenerator");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.springframework.messaging");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.springframework.web.socket");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.springframework.web.socket.sockjs.support.AbstractSockJsService");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.glassfish.jaxb.runtime.v2.model.impl.RuntimeBuiltinLeafInfoImpl");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=com.github.jaiimageio");

        // Lucene MMapDirectory fix: ensure NIOFSDirectory is used in native image
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.apache.lucene");
        // Disable Lucene MemorySegment-based MMapDirectory (Arena.ofShared not supported in GraalVM native image)
        addBuildArg(buildArgsDom, "-J-Dorg.apache.lucene.store.MMapDirectory.enableMemorySegments=false");

        // Static UI resources
        addBuildArg(buildArgsDom, "-H:IncludeResources=static/.*");
        addBuildArg(buildArgsDom, "-H:IncludeResources=application\\.properties");
        addBuildArg(buildArgsDom, "-H:IncludeResources=application-.*\\.properties");
        addBuildArg(buildArgsDom, "-H:IncludeResources=org/bytedeco/.*");
        addBuildArg(buildArgsDom, "-H:IncludeResources=org/nd4j/.*");
        addBuildArg(buildArgsDom, "-H:IncludeResources=model-sources\\.yml");

        nativePluginConfig.addChild(buildArgsDom);
        nativeMavenPlugin.setConfiguration(nativePluginConfig);

        PluginExecution nativeBuildExecution = new PluginExecution();
        nativeBuildExecution.setId("build-native");
        nativeBuildExecution.addGoal("compile-no-fork");
        nativeBuildExecution.setPhase("package");
        nativeMavenPlugin.addExecution(nativeBuildExecution);

        nativeProfileBuild.addPlugin(nativeMavenPlugin);
        nativeProfile.setBuild(nativeProfileBuild);
        model.addProfile(nativeProfile);

        // Add subprocess native image profiles
        addSubprocessNativeProfile("native-ingest",
                "ai.kompile.app.subprocess.IngestSubprocessMain", "kompile-ingest");
        addSubprocessNativeProfile("native-vector",
                "ai.kompile.app.subprocess.VectorPopulationSubprocessMain", "kompile-vector");
        addSubprocessNativeProfile("native-embedding",
                "ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessMain", "kompile-embedding");
        addSubprocessNativeProfile("native-model-init",
                "ai.kompile.app.subprocess.model.ModelInitSubprocessMain", "kompile-model-init");
    }

    private void addSubprocessNativeProfile(String profileId, String mainClass, String imageName) {
        Profile profile = new Profile();
        profile.setId(profileId);
        Build build = new Build();

        Plugin nativeMavenPlugin = createPlugin("org.graalvm.buildtools", "native-maven-plugin",
                "${native-maven-plugin.version}");
        nativeMavenPlugin.setExtensions(true);

        Xpp3Dom config = new Xpp3Dom("configuration");
        addChild(config, "imageName", imageName);
        addChild(config, "mainClass", mainClass);

        Xpp3Dom buildArgsDom = new Xpp3Dom("buildArgs");
        addBuildArg(buildArgsDom, "-J-Xmx18g");
        addBuildArg(buildArgsDom, "--no-fallback");
        addBuildArg(buildArgsDom, "--allow-incomplete-classpath");
        addBuildArg(buildArgsDom, "-H:+ReportExceptionStackTraces");
        addBuildArg(buildArgsDom, "-Dorg.bytedeco.javacpp.nopointergc=true");
        addBuildArg(buildArgsDom, "--initialize-at-build-time=org.slf4j.LoggerFactory,ch.qos.logback.classic.LoggerContext,ch.qos.logback.classic.spi.StaticLoggerBinder,ch.qos.logback.core.spi.StatusManager");
        addBuildArg(buildArgsDom, "--initialize-at-build-time=org.slf4j.helpers");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.bytedeco.javacpp.Loader");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.eclipse.deeplearning4j");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.nd4j");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.apache.lucene");
        addBuildArg(buildArgsDom, "-J-Dorg.apache.lucene.store.MMapDirectory.enableMemorySegments=false");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.bytedeco.javacpp.Pointer");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.bytedeco.javacpp.Pointer$NativeDeallocator");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.bytedeco.javacpp.PointerScope");
        addBuildArg(buildArgsDom, "-H:IncludeResources=org/bytedeco/.*");
        addBuildArg(buildArgsDom, "-H:IncludeResources=org/nd4j/.*");
        addBuildArg(buildArgsDom, "-H:IncludeResources=META-INF/services/.*");
        addBuildArg(buildArgsDom, "-H:IncludeResources=META-INF/native-image/.*\\.json");
        config.addChild(buildArgsDom);

        nativeMavenPlugin.setConfiguration(config);

        PluginExecution execution = new PluginExecution();
        execution.setId("build-" + profileId);
        execution.addGoal("compile-no-fork");
        execution.setPhase("package");
        nativeMavenPlugin.addExecution(execution);

        build.addPlugin(nativeMavenPlugin);
        profile.setBuild(build);
        model.addProfile(profile);
    }

    private void addSpringRepositories() {
        Repository springMilestones = new Repository();
        springMilestones.setId("spring-milestones");
        springMilestones.setName("Spring Milestones");
        springMilestones.setUrl("https://repo.spring.io/milestone");
        RepositoryPolicy snapshotsPolicyMilestones = new RepositoryPolicy();
        snapshotsPolicyMilestones.setEnabled(false);
        springMilestones.setSnapshots(snapshotsPolicyMilestones);
        model.addRepository(springMilestones);

        Repository springReleases = new Repository();
        springReleases.setId("spring-releases");
        springReleases.setName("Spring Releases");
        springReleases.setUrl("https://repo.spring.io/release");
        RepositoryPolicy snapshotsPolicyReleases = new RepositoryPolicy();
        snapshotsPolicyReleases.setEnabled(false);
        springReleases.setSnapshots(snapshotsPolicyReleases);
        model.addRepository(springReleases);
    }

    private Plugin createPlugin(String groupId, String artifactId, String version) {
        Plugin plugin = new Plugin();
        plugin.setGroupId(groupId);
        plugin.setArtifactId(artifactId);
        if (version != null && !version.isEmpty()) {
            plugin.setVersion(version);
        }
        return plugin;
    }


    public static void main(String... args) {
        int exitCode = new CommandLine(new RagPomGenerator()).execute(args);
        System.exit(exitCode);
    }
}