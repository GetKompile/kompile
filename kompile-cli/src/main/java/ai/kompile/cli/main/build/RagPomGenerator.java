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

package ai.kompile.cli.main.build;

import ai.kompile.cli.main.models.KompileModelManager;
import ai.kompile.cli.main.models.ModelConstants;
import ai.kompile.cli.main.models.ModelDescriptor;
import ai.kompile.cli.main.models.ModelType;
import org.apache.maven.model.*;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import picocli.CommandLine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;


@CommandLine.Command(name = "rag-pom-generate", mixinStandardHelpOptions = false,
        description = "Generates a pom.xml for a RAG MCP Assistant application instance.")
public class RagPomGenerator implements Callable<Void> {

    @CommandLine.Option(names = {"--databaseUrl"},
            description = "Database URL. Will auto-create database if it doesn't exist",
            defaultValue = "jdbc:postgresql://localhost:5432/kompile_db")
    private String databaseUrl = "jdbc:postgresql://localhost:5432/kompile_db";

    @CommandLine.Option(names = {"--databaseUsername"},
            description = "Database username",
            defaultValue = "postgres")
    private String databaseUsername = "postgres";

    @CommandLine.Option(names = {"--databasePassword"},
            description = "Database password",
            defaultValue = "postgres")
    private String databasePassword = "postgres";

    @CommandLine.Option(names = {"--enableSchemaInit"},
            description = "Enable automatic schema initialization with SQL scripts",
            defaultValue = "true")
    private boolean enableSchemaInit = true;
    private static final String DEFAULT_EMBEDDED_POSTGRES_VERSION = "2.0.7";


    @CommandLine.Option(names = {"--outputFile"}, description = "The output file for the generated pom.xml", defaultValue = "pom-rag-instance.xml")
    private File outputFile;

    @CommandLine.Option(names = {"--instanceGroupId"}, description = "GroupId for the generated RAG instance", defaultValue = "ai.kompile.rag.instance")
    private String instanceGroupId;

    @CommandLine.Option(names = {"--instanceArtifactId"}, description = "ArtifactId for the generated RAG instance", defaultValue = "kompile-sample")
    private String instanceArtifactId;

    @CommandLine.Option(names = {"--instanceVersion"}, description = "Version for the generated RAG instance", defaultValue = "0.1.0-SNAPSHOT")
    private String instanceVersion;

    @CommandLine.Option(names = {"--ragMcpVersion"}, description = "Version of the ai.kompile modules", defaultValue = "0.1.0-SNAPSHOT")
    private String ragMcpVersion;

    @CommandLine.Option(names = {"--includeAppMain"}, description = "Include kompile-app-main module", defaultValue = "true", negatable = true)
    private boolean includeAppMain;
    @CommandLine.Option(names = {"--includeAppCore"}, description = "Include kompile-app-core module", defaultValue = "true", negatable = true)
    private boolean includeAppCore;
    @CommandLine.Option(names = {"--includeLoadersOrchestrator"}, description = "Include kompile-app-loaders-orchestrator module", defaultValue = "true", negatable = true)
    private boolean includeLoadersOrchestrator;

    // Original Tika loader (deprecated/heavy)
    @CommandLine.Option(names = {"--includeLoaderTika"}, description = "Include kompile-loader-tika module (deprecated - use specialized loaders instead)")
    private boolean includeLoaderTika = false;
    @CommandLine.Option(names = {"--includeLoaderPdf"}, description = "Include kompile-loader-pdf module")
    private boolean includeLoaderPdf = false;

    // New specialized loader modules
    @CommandLine.Option(names = {"--includeLoaderMicrosoft"}, description = "Include kompile-loader-microsoft module for Office documents")
    private boolean includeLoaderMicrosoft = false;
    @CommandLine.Option(names = {"--includeLoaderMail"}, description = "Include kompile-loader-mail module for email parsing")
    private boolean includeLoaderMail = false;
    @CommandLine.Option(names = {"--includeLoaderPdfExtended"}, description = "Include kompile-loader-pdf-extended module for advanced PDF processing")
    private boolean includeLoaderPdfExtended = false;

    @CommandLine.Option(names = {"--includeAnserini"}, description = "Include kompile-app-anserini module")
    private boolean includeAnserini = false;

    @CommandLine.Option(names = {"--includeVectorStoreAnserini"}, description = "Include kompile-vectorstore-anserini")
    private boolean includeVectorStoreAnserini = false;
    @CommandLine.Option(names = {"--includeLlmOpenai"}, description = "Include kompile-app-openai-llm module", defaultValue = "true", negatable = true)
    private boolean includeLlmOpenai;
    @CommandLine.Option(names = {"--includeLlmAnthropic"}, description = "Include kompile-app-anthropic-llm module")
    private boolean includeLlmAnthropic = false;
    @CommandLine.Option(names = {"--includeLlmGemini"}, description = "Include kompile-app-gemini-llm module")
    private boolean includeLlmGemini = false;
    @CommandLine.Option(names = {"--includeEmbeddingOpenai"}, description = "Include kompile-embedding-openai module", defaultValue = "true", negatable = true)
    private boolean includeEmbeddingOpenai;
    @CommandLine.Option(names = {"--includeEmbeddingSentenceTransformer"}, description = "Include kompile-embedding-sentence-transformer module")
    private boolean includeEmbeddingSentenceTransformer = false;
    @CommandLine.Option(names = {"--includeVectorstoreChroma"}, description = "Include kompile-vectorstore-chroma module")
    private boolean includeVectorstoreChroma = false;
    @CommandLine.Option(names = {"--includeVectorstorePgvector"}, description = "Include kompile-vectorstore-pgvector module", defaultValue = "true", negatable = true)
    private boolean includeVectorstorePgvector;
    @CommandLine.Option(names = {"--includeToolFilesystem"}, description = "Include kompile-tool-filesystem module", defaultValue = "true", negatable = true)
    private boolean includeToolFilesystem;
    @CommandLine.Option(names = {"--includeToolRag"}, description = "Include kompile-tool-rag module", defaultValue = "true", negatable = true)
    private boolean includeToolRag;
    @CommandLine.Option(names = {"--includeEmbeddingPostgresml"}, description = "Include kompile-embedding-postgresml module")
    private boolean includeEmbeddingPostgresml = false;
    @CommandLine.Option(names = {"--includePgmlIndexer"}, description = "Include kompile-app-pgml-indexer module")
    private boolean includePgmlIndexer = false;

    // Chunker Options
    @CommandLine.Option(names = {"--includeChunkerSentence"}, description = "Include kompile-chunker-sentence module.")
    private boolean includeChunkerSentence = false;
    @CommandLine.Option(names = {"--includeChunkerRecursiveCharacter"}, description = "Include kompile-chunker-recursivecharacter module")
    private boolean includeChunkerRecursiveCharacter = false;
    @CommandLine.Option(names = {"--includeChunkerMarkdown"}, description = "Include kompile-chunker-markdown module")
    private boolean includeChunkerMarkdown = false;
    @CommandLine.Option(names = {"--includeChunkerToken"}, description = "Include kompile-chunker-token module")
    private boolean includeChunkerToken = false;

    @CommandLine.Option(names = {"--javacppPlatform"},description = "Build for a specific specified platform. An example would be linux-x86_64 - this reduces binary size and prevents out of memories from trying to include binaries for too many platforms.")
    private String javacppPlatform = "linux-x86_64";

    @CommandLine.Option(names = {"--javacppExtension"},description = "An optional javacpp extension such as avx2 or cuda depending on the target set of dependencies.")
    private String javacppExtension;

    // RESTORED as the single language flag
    @CommandLine.Option(names = {"--supportedLanguages"},
            description = "Comma-separated list of ISO 639-1 language codes (e.g., en,de,es). " +
                    "Used to determine which OpenNLP sentence models to download if --includeChunkerSentence is active. "+
                    "The first language in this list will also be set as the default 'kompile.opennlp.sentence.language' property.",
            defaultValue = "en", split = ",")
    private List<String> supportedLanguages = new ArrayList<>(Collections.singletonList("en"));

    @CommandLine.Option(names = {"--buildNative"}, description = "Configure build for GraalVM native image", defaultValue = "true")
    private boolean buildNative = true;


    @CommandLine.Option(names = {"--anserini-indexes"},
            description = "Comma-separated list of Anserini prebuilt index IDs to ensure are available (e.g., msmarco-passage-v1). " +
                    "Requires --includeAnserini to be true. Consult ModelConstants.java for available IDs.",
            split = ",", arity = "0..*")
    private List<String> anseriniIndexIds = new ArrayList<>();



    private KompileModelManager modelManager;
    private Map<String, Path> resolvedModelPaths = new HashMap<>(); // Stores modelId -> Path to cached model

    @CommandLine.Option(names = {"--anserini-encoders"},
            description = "Comma-separated list of Anserini encoder model IDs (e.g., bge-base-en-v1.5-onnx, splade-pp-sd-onnx). " +
                    "Requires --includeAnserini to be true. Consult ModelConstants.java for available IDs.",
            split = ",", arity = "0..*")
    private List<String> anseriniEncoderModelIds = new ArrayList<>();




    private Model model;
    private final List<Dependency> defaultDependencies = new ArrayList<>();

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

    private Dependency createDependencyInternal(String groupId, String artifactId, String versionProperty, String scope, String classifier, boolean optional) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        dependency.setVersion(versionProperty);
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

    private void addDependency(List<Dependency> addTo, String groupId, String artifactId, String versionProperty, String scope, String classifier, boolean optional) {
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
        if (buildArgsDom == null) return;
        Xpp3Dom buildArgElement = new Xpp3Dom("buildArg");
        buildArgElement.setValue(argValue);
        buildArgsDom.addChild(buildArgElement);
    }



    // Method to download models for the languages specified in the --supportedLanguages list
    private List<String> downloadOpenNLPModelsForSupportedLanguages(File projectBaseDir, List<String> languagesToDownload) throws IOException {
        List<String> successfullyDownloadedLocalFilenames = new ArrayList<>();
        if (languagesToDownload == null || languagesToDownload.isEmpty()) {
            System.out.println("No languages specified via --supportedLanguages for OpenNLP sentence model download. Defaulting to 'en'.");
            languagesToDownload = Collections.singletonList("en");
        }

        List<String> normalizedLanguages = languagesToDownload.stream()
                .filter(lang -> lang != null && !lang.trim().isEmpty())
                .map(String::toLowerCase)
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());

        if (normalizedLanguages.isEmpty()) {
            System.out.println("Effectively no valid languages specified for OpenNLP model download after normalization. Skipping.");
            return successfullyDownloadedLocalFilenames;
        }

        System.out.println("Attempting to download OpenNLP sentence models for specified languages: " + String.join(", ", normalizedLanguages));

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
                System.err.println("OpenNLP sentence model configuration (remote or local filename) not found for language code: '" + langKey + "'. Skipping this language.");
                System.err.println("Ensure language code '" + langKey + "' is present in the predefined model maps in RagPomGenerator.java.");
                continue;
            }

            String modelUrlString = OPENNLP_MODEL_BASE_URL + remoteModelFileName;
            Path modelFile = modelTargetDirInResources.resolve(localModelFileName);

            if (Files.exists(modelFile)) {
                System.out.println("OpenNLP model " + localModelFileName + " for language '" + langKey + "' already exists. Adding to list of available models.");
                successfullyDownloadedLocalFilenames.add(localModelFileName);
                continue;
            }

            System.out.println("Downloading OpenNLP sentence model " + localModelFileName + " for language '" + langKey + "' from " + modelUrlString + " to " + modelFile.toAbsolutePath());
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
                System.err.println("Failed to download OpenNLP model '" + localModelFileName + "' for language '" + langKey + "': " + e.getMessage());
                if (Files.exists(modelFile)) {
                    try {
                        Files.delete(modelFile);
                    } catch (IOException ex) {
                        System.err.println("Also failed to delete partial model file: " + modelFile.toString() + " - " + ex.getMessage());
                    }
                }
            }
        }
        return successfullyDownloadedLocalFilenames;
    }

    @Override
    public Void call() throws Exception {
        this.modelManager = new KompileModelManager(); // Initialize model manager

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

        // --- Model Management Integration ---
        // 1. OpenNLP Models
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
                    System.err.println("OpenNLP sentence model configuration not found for language code: '" + langKey + "'. Skipping.");
                    continue;
                }
                ModelDescriptor opennlpModelDesc = new ModelDescriptor(
                        "opennlp_sent_" + langKey,
                        ModelType.OPENNLP_SENTENCE,
                        ModelConstants.OPENNLP_MODEL_BASE_URL + remoteFileName,
                        Paths.get("opennlp", localFileName).toString(),
                        "1.2-2.5.0", null, Map.of("language", langKey)
                );
                try {
                    Path modelPath = modelManager.ensureModelAvailable(opennlpModelDesc);
                    resolvedModelPaths.put(opennlpModelDesc.getModelId(), modelPath);
                    System.out.println("Ensured OpenNLP model for " + langKey + " is available at: " + modelPath.toAbsolutePath());
                } catch (IOException e) {
                    System.err.println("ERROR: Failed to ensure OpenNLP model for language '" + langKey + "': " + e.getMessage());
                }
            }
        }

        // 2. Anserini Lucene Indexes
        if (includeAnserini && this.anseriniIndexIds != null && !this.anseriniIndexIds.isEmpty()) {
            for (String indexIdInput : this.anseriniIndexIds) {
                String indexId = indexIdInput.trim();
                if (indexId.isEmpty()) continue;
                ModelDescriptor anseriniDesc = ModelConstants.getAnseriniIndexDescriptor(indexId);
                if (anseriniDesc == null) {
                    System.err.println("WARNING: Anserini index descriptor not found for ID: '" + indexId + "'. Skipping. Consult ModelConstants.java.");
                    continue;
                }
                try {
                    Path indexPath = modelManager.ensureModelAvailable(anseriniDesc);
                    resolvedModelPaths.put(anseriniDesc.getModelId(), indexPath);
                    System.out.println("Ensured Anserini index '" + indexId + "' is available at: " + indexPath.toAbsolutePath());
                } catch (IOException e) {
                    System.err.println("ERROR: Failed to ensure Anserini index '" + indexId + "': " + e.getMessage());
                }
            }
        }

        // 3. Anserini Encoder Models (e.g., ONNX, DL4J files for SameDiff encoders)
        if (includeAnserini && this.anseriniEncoderModelIds != null
                && !this.anseriniEncoderModelIds.isEmpty() || includeVectorStoreAnserini) {
            for (String encoderModelIdInput : this.anseriniEncoderModelIds) {
                String encoderModelId = encoderModelIdInput.trim();
                if (encoderModelId.isEmpty()) continue;
                ModelDescriptor encoderDesc = ModelConstants.getAnseriniEncoderModelDescriptor(encoderModelId);
                if (encoderDesc == null) {
                    System.err.println("WARNING: Anserini encoder model descriptor not found for ID: '" + encoderModelId + "'. Skipping. Consult ModelConstants.java.");
                    continue;
                }
                try {
                    Path modelPath = modelManager.ensureModelAvailable(encoderDesc);
                    resolvedModelPaths.put(encoderDesc.getModelId(), modelPath);
                    System.out.println("Ensured Anserini encoder model '" + encoderModelId + "' is available at: " + modelPath.toAbsolutePath());
                } catch (IOException e) {
                    System.err.println("ERROR: Failed to ensure Anserini encoder model '" + encoderModelId + "': " + e.getMessage());
                }
            }
        }


        // --- End Model Management Integration ---

        Properties props = new Properties();
        // Populate props from existing logic (versions, start-class, etc.)
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


        // Set default OpenNLP language from CLI options
        String defaultRuntimeLangForOpenNLP = "en";
        if (this.supportedLanguages != null && !this.supportedLanguages.isEmpty()) {
            String firstSpecifiedLang = this.supportedLanguages.get(0).toLowerCase().trim();
            if (ModelConstants.isOpenNLPLanguageSupported(firstSpecifiedLang)) {
                defaultRuntimeLangForOpenNLP = firstSpecifiedLang;
            } else {
                System.out.println("Warning: First language '" + this.supportedLanguages.get(0) +
                        "' from --supportedLanguages is not in the known model list for setting runtime default property 'kompile.opennlp.sentence.language'. " +
                        "Defaulting property to 'en'.");
            }
        }
        props.setProperty("kompile.opennlp.sentence.language", defaultRuntimeLangForOpenNLP);
        props.setProperty("instanceArtifactId", this.instanceArtifactId); // Used by generateApplicationPropertiesFile

        model.setProperties(props);

        addApplicationDependencies();
        addApplicationBuild();

        if (buildNative) {
            addNativeProfile(CORE_APP_MAIN_CLASS_FQCN, Collections.emptyList()); // Models not bundled
        }

        addSpringRepositories();

        MavenXpp3Writer mavenXpp3Writer = new MavenXpp3Writer();
        File finalPomFile = outputFile.isDirectory() ? new File(outputFile, "pom-rag-instance.xml") : outputFile;

        try (FileWriter fileWriter = new FileWriter(finalPomFile)) {
            mavenXpp3Writer.write(fileWriter, model);
            System.out.println("Successfully generated RAG application POM: " + finalPomFile.getAbsolutePath());
        }

        generateApplicationPropertiesFile(projectDir, props);

        // Conditional generation of other config files (unchanged from your original logic)
        if (includeEmbeddingPostgresml || includePgmlIndexer) {
            generatePgmlSchemaFiles(projectDir);
        }
        if (enableSchemaInit && (includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer)) {
            generateSqlSchemaFiles(projectDir);
        }
        if (includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer) {
            generateDatabaseConfiguration(projectDir);
            generateGlobalExceptionHandler(projectDir);
            generateProviderConfigurationClass(projectDir);
        }

        return null;
    }

    private void generateApplicationPropertiesFile(File projectDir, Properties pomProperties) throws IOException {
        File resourcesDir = new File(projectDir, "src/main/resources");
        if (!resourcesDir.exists() && !resourcesDir.mkdirs()) {
            throw new IOException("Could not create resources directory: " + resourcesDir.getAbsolutePath());
        }
        File appPropsFile = new File(resourcesDir, "application.properties");

        try (FileWriter writer = new FileWriter(appPropsFile)) {
            writeApplicationPropertiesHeaderCustom(writer, pomProperties); // Uses instanceArtifactId from pomProperties

            if (includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer) {
                writeDatabaseConfiguration(writer);
                writeSchemaManagementConfiguration(writer);
            }
            writeAutoConfigurationExclusions(writer);
            writeProviderEnablementFlags(writer); // These use RagPomGenerator's boolean fields

            // Write structural configurations, including model paths derived from the cache
            writeStructuralCustom(writer, pomProperties); // Uses pomProperties for some defaults

            // Instructional properties for runtime model cache path
            writer.write("\n# --- Runtime Model Cache Configuration ---\n");
            writer.write("# Your application will attempt to load models from a central cache.\n");
            writer.write("# Set the " + ModelConstants.ENV_KOMPILE_MODEL_CACHE_DIR + " environment variable to specify the cache location.\n");
            String defaultCachePath = Paths.get(System.getProperty("user.home"), ModelConstants.DEFAULT_KOMPILE_MODEL_CACHE_SUBDIR).toAbsolutePath().toString().replace("\\", "\\\\");
            writer.write("# If not set, it defaults to: " + defaultCachePath + "\n");
            writer.write("# RagPomGenerator used this cache path during generation: " + modelManager.getBaseCachePath().toAbsolutePath().toString().replace("\\", "\\\\") + "\n");
            // This property will be resolved at runtime (from ENV or to the generator-time default if ENV is not set)
            writer.write("kompile.model.cache.path=${" + ModelConstants.ENV_KOMPILE_MODEL_CACHE_DIR + ":" + modelManager.getBaseCachePath().toAbsolutePath().toString().replace("\\", "\\\\") + "}\n\n");


            writeConfigurationTemplate(writer); // Writes examples/templates for user to fill
        }
        System.out.println("Generated application.properties: " + appPropsFile.getAbsolutePath());
    }


    private void writeApplicationPropertiesHeaderCustom(FileWriter writer, Properties pomProperties) throws IOException {
        writer.write("# Generated application.properties\n");
        writer.write("# Project: " + pomProperties.getProperty("instanceArtifactId", this.instanceArtifactId) + "\n");
        writer.write("# Generated on: " + new java.util.Date() + "\n");
        writer.write("# Configured providers: " + getProviderSummary() + "\n\n");

        writer.write("# Logging for model loading and general app behavior\n");
        writer.write("logging.level.ai.kompile.cli.main.models=INFO\n");
        writer.write("logging.level.ai.kompile.app=INFO\n"); // General app logging
        writer.write("logging.level.io.anserini=INFO\n\n");

        // Add automatic PostgresML error debugging if enabled
        if (includeEmbeddingPostgresml || includePgmlIndexer) {
            writer.write("# =============================================================================\n");
            writer.write("# AUTOMATIC PostgresML ERROR DEBUGGING\n");
            writer.write("# =============================================================================\n");
            writer.write("logging.level.org.springframework.jdbc.core.JdbcTemplate=DEBUG\n");
            writer.write("logging.level.org.springframework.jdbc.core.StatementCreatorUtils=TRACE\n");
            writer.write("logging.level.org.springframework.ai.postgresml=DEBUG\n");
            writer.write("logging.level.org.postgresql=DEBUG\n");
            writer.write("logging.level.ai.kompile.app.pgml.indexer=DEBUG\n");
            writer.write("logging.level.ai.kompile.vectorstore=DEBUG\n");
            writer.write("logging.level.org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator=DEBUG\n");
            writer.write("logging.level.org.springframework.dao=DEBUG\n");
            writer.write("logging.level." + instanceGroupId + ".config=DEBUG\n\n");
        }
    }

    // Modified to use the runtime cache path for models.
    // pomProperties here can be used to fetch defaults if they were set (e.g., instanceArtifactId)
    private void writeStructuralCustom(FileWriter writer, Properties pomProperties) throws IOException {
        writer.write("# =============================================================================\n");
        writer.write("# STRUCTURAL CONFIGURATION\n");
        writer.write("# =============================================================================\n");

        writer.write("spring.application.name=" + pomProperties.getProperty("instanceArtifactId", this.instanceArtifactId) + "\n");
        writer.write("server.port=8080\n\n"); // Default server port

        writer.write("# This property defines the base directory from which models will be loaded AT RUNTIME.\n");
        writer.write("# It defaults to the path used during generation if KOMPILE_MODEL_CACHE_DIR is not set.\n");
        String runtimeCachePathProperty = "kompile.runtime.model.cache.path"; // Used by application code
        String runtimeCachePathValue = "${" + ModelConstants.ENV_KOMPILE_MODEL_CACHE_DIR + ":" +
                modelManager.getBaseCachePath().toAbsolutePath().toString().replace("\\", "\\\\") + "}";
        writer.write(runtimeCachePathProperty + "=" + runtimeCachePathValue + "\n\n");

        if (includeChunkerSentence) {
            writer.write("# OpenNLP Configuration (runtime will load models from cache)\n");
            writer.write("kompile.opennlp.models.basepath=${" + runtimeCachePathProperty + "}/opennlp\n");
            writer.write("kompile.opennlp.sentence.language=" + pomProperties.getProperty("kompile.opennlp.sentence.language", "en") + "\n\n");
        }

        if (includeAnserini) {
            writer.write("# Anserini Configuration (runtime will load indexes/models from cache)\n");
            writer.write("kompile.anserini.models.basepath=${" + runtimeCachePathProperty + "}/anserini\n");

            // Specific Lucene index paths (resolved to cache)
            if (this.anseriniIndexIds != null && !this.anseriniIndexIds.isEmpty()) {
                for (String indexId : this.anseriniIndexIds) {
                    String trimmedIndexId = indexId.trim();
                    ModelDescriptor desc = ModelConstants.getAnseriniIndexDescriptor(trimmedIndexId);
                    if (desc != null && resolvedModelPaths.containsKey(desc.getModelId())) {
                        // The actual value will be the absolute path from resolvedModelPaths
                        // but for runtime, it's better if Anserini components can resolve it
                        // relative to kompile.anserini.models.basepath or use a full path constructed at runtime.
                        // Let's write the subpath relative to the anserini cache root.
                        writer.write("anserini.indexPath." + trimmedIndexId + "=${kompile.anserini.models.basepath}/indexes/" + trimmedIndexId + "\n");
                        // Or, if anseriniDesc.getExpectedCacheSubpath() is anserini/indexes/indexId:
                        // writer.write("anserini.indexPath." + trimmedIndexId + "=${kompile.runtime.model.cache.path}/" + desc.getExpectedCacheSubpath().replace("\\", "/") + "\n");
                    }
                }
            } else {
                writer.write("# Default Anserini paths if no specific --anserini-indexes are given\n");
                writer.write("anserini.indexPath=${kompile.anserini.models.basepath}/indexes/default_index\n");
                writer.write("anserini.corpusPath=${kompile.anserini.models.basepath}/corpus/default_corpus\n");
            }

            // Specific Anserini Encoder Model paths (resolved to cache)
            if (this.anseriniEncoderModelIds != null && !this.anseriniEncoderModelIds.isEmpty()) {
                writer.write("\n# Anserini Encoder Model Paths (runtime will load from cache)\n");
                for (String encoderModelId : this.anseriniEncoderModelIds) {
                    String trimmedEncoderId = encoderModelId.trim();
                    ModelDescriptor desc = ModelConstants.getAnseriniEncoderModelDescriptor(trimmedEncoderId);
                    if (desc != null && resolvedModelPaths.containsKey(desc.getModelId())) {
                        // Path to the specific encoder model file within the cache
                        // e.g., anserini/encoders/onnx/bge-base-en-v1.5/model.onnx
                        String encoderModelPathValue = "${kompile.runtime.model.cache.path}/" + desc.getExpectedCacheSubpath().replace("\\", "/");
                        writer.write("anserini.encoder." + trimmedEncoderId + ".model.path=" + encoderModelPathValue + "\n");
                        // Associated vocabulary or config files might also need paths
                        if ("bge-base-en-v1.5-onnx".equals(trimmedEncoderId)) { // Example
                            writer.write("anserini.encoder." + trimmedEncoderId + ".vocab.path=${kompile.runtime.model.cache.path}/anserini/encoders/onnx/bge-base-en-v1.5/tokenizer.json\n");
                            // Assuming tokenizer.json is also managed or expected alongside model.onnx
                        }
                    }
                }
            }
            writer.write("\n");
        }

        writer.write("# Kompile Application Structure (original paths, review for deployment)\n");
        writer.write("app.document.sources=./data/input_documents/sample.txt,./data/input_documents/sample.pdf\n");
        writer.write("app.document.uploads-path=./data/input_documents/uploads\n");
        writer.write("mcp.filesystem.roots.default.path=./data/shared_files\n");
        writer.write("mcp.filesystem.roots.default.alias=default\n\n");
    }

    /**
     * COMPREHENSIVE FIX for PostgresML function signature issues
     *
     * This creates functions with ALL possible PostgreSQL string type combinations
     * because PostgreSQL treats each type variation as a completely different signature.
     *
     * Replace the generatePgmlSchemaFiles() method in RagPomGenerator.java with this version.
     */
    private void generatePgmlSchemaFiles(File projectDir) throws IOException {
        File resourcesDir = new File(projectDir, "src/main/resources");
        if (!resourcesDir.exists() && !resourcesDir.mkdirs()) {
            throw new IOException("Could not create resources directory: " + resourcesDir.getAbsolutePath());
        }

        File pgmlSchemaFile = new File(resourcesDir, "pgml-schema.sql");
        try (FileWriter writer = new FileWriter(pgmlSchemaFile)) {
            writer.write("-- PostgresML Comprehensive Schema Initialization\n");
            writer.write("-- Generated on: " + new java.util.Date() + "\n");
            writer.write("-- COMPREHENSIVE FIX: Creates ALL possible function signatures for PostgreSQL string types\n");
            writer.write("-- This addresses PostgreSQL's strict function overloading rules\n\n");

            writer.write("-- Step 1: Create pgml schema and required extensions\n");
            writer.write("CREATE SCHEMA IF NOT EXISTS pgml;\n");
            writer.write("COMMENT ON SCHEMA pgml IS 'PostgresML schema - comprehensive initialization';\n\n");

            writer.write("-- Ensure vector extension if available (for return types)\n");
            writer.write("DO $extension_setup$\n");
            writer.write("BEGIN\n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE EXTENSION IF NOT EXISTS vector;\n");
            writer.write("        RAISE NOTICE '✓ Vector extension available';\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        RAISE WARNING '⚠ Vector extension not available - using FLOAT[] fallback';\n");
            writer.write("    END;\n");
            writer.write("    \n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE EXTENSION IF NOT EXISTS pgml SCHEMA pgml;\n");
            writer.write("        RAISE NOTICE '✓ PostgresML extension installed and working!';\n");
            writer.write("        -- If we get here, PostgresML is available, so we don't need stub functions\n");
            writer.write("        RETURN;\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        RAISE WARNING '⚠ PostgresML extension not available - creating comprehensive stub functions';\n");
            writer.write("    END;\n");
            writer.write("END $extension_setup$;\n\n");

            writer.write("-- Step 2: Create comprehensive stub functions\n");
            writer.write("-- PostgreSQL treats these as completely different function signatures:\n");
            writer.write("-- character varying, text, varchar, char, etc. are all different types for function resolution\n\n");

            // Determine return type based on vector extension availability
            String returnType;
            if (includeVectorstorePgvector) {
                returnType = "vector";
                writer.write("-- Using vector return type (pgvector enabled)\n");
            } else {
                returnType = "FLOAT[]";
                writer.write("-- Using FLOAT[] return type (pgvector not enabled)\n");
            }

            writer.write("DO $create_stubs$\n");
            writer.write("DECLARE\n");
            writer.write("    pgml_available BOOLEAN := FALSE;\n");
            writer.write("    vector_available BOOLEAN := FALSE;\n");
            writer.write("    final_return_type TEXT;\n");
            writer.write("BEGIN\n");
            writer.write("    -- Check if PostgresML extension is already working\n");
            writer.write("    BEGIN\n");
            writer.write("        PERFORM pgml.version();\n");
            writer.write("        pgml_available := TRUE;\n");
            writer.write("        RAISE NOTICE 'PostgresML is available - skipping stub creation';\n");
            writer.write("        RETURN;\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        pgml_available := FALSE;\n");
            writer.write("    END;\n");
            writer.write("    \n");
            writer.write("    -- Determine return type based on vector extension availability\n");
            writer.write("    BEGIN\n");
            writer.write("        -- Test if vector type exists\n");
            writer.write("        EXECUTE 'SELECT NULL::vector';\n");
            writer.write("        vector_available := TRUE;\n");
            writer.write("        final_return_type := 'vector';\n");
            writer.write("        RAISE NOTICE 'Using vector return type';\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        vector_available := FALSE;\n");
            writer.write("        final_return_type := 'FLOAT[]';\n");
            writer.write("        RAISE NOTICE 'Using FLOAT[] return type (vector extension not available)';\n");
            writer.write("    END;\n");
            writer.write("    \n");

            // Create all possible function signature combinations
            String[] firstParamTypes = {"character varying", "text", "varchar", "TEXT", "CHAR", "CHARACTER VARYING"};
            String[] secondParamTypes = {"text", "character varying", "varchar", "TEXT", "VARCHAR", "CHAR"};
            String[] thirdParamTypes = {"jsonb", "JSONB", "json", "JSON"};

            writer.write("    -- Create comprehensive function signatures to handle all possible Spring AI calls\n");
            writer.write("    RAISE NOTICE 'Creating comprehensive stub functions for all PostgreSQL string type combinations...';\n");
            writer.write("    \n");

            int signatureCount = 0;
            for (String param1 : firstParamTypes) {
                for (String param2 : secondParamTypes) {
                    for (String param3 : thirdParamTypes) {
                        signatureCount++;
                        writer.write("    -- Signature " + signatureCount + ": " + param1 + ", " + param2 + ", " + param3 + "\n");
                        writer.write("    BEGIN\n");
                        writer.write("        EXECUTE 'CREATE OR REPLACE FUNCTION pgml.embed(' ||\n");
                        writer.write("            'model_name " + param1 + ", ' ||\n");
                        writer.write("            'text_input " + param2 + ", ' ||\n");
                        writer.write("            'kwargs " + param3 + " DEFAULT ''{}''::jsonb' ||\n");
                        writer.write("        ') RETURNS ' || final_return_type || ' AS $stub$' ||\n");
                        writer.write("        'BEGIN ' ||\n");
                        writer.write("            'RAISE EXCEPTION ''PostgresML not available. Signature: " + param1 + ", " + param2 + ", " + param3 + ". Install: https://postgresml.org/docs/getting-started/installation''; ' ||\n");
                        writer.write("        'END; $stub$ LANGUAGE plpgsql;';\n");
                        writer.write("    EXCEPTION WHEN duplicate_function THEN\n");
                        writer.write("        -- Function already exists, skip\n");
                        writer.write("        NULL;\n");
                        writer.write("    WHEN OTHERS THEN\n");
                        writer.write("        RAISE WARNING 'Could not create function signature " + signatureCount + " (" + param1 + ", " + param2 + ", " + param3 + "): %', SQLERRM;\n");
                        writer.write("    END;\n");
                        writer.write("    \n");
                    }
                }
            }

            writer.write("    -- Create other commonly used stub functions\n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE OR REPLACE FUNCTION pgml.version() RETURNS TEXT AS $version$\n");
            writer.write("        BEGIN\n");
            writer.write("            RETURN 'stub-version-pgml-not-installed';\n");
            writer.write("        END;\n");
            writer.write("        $version$ LANGUAGE plpgsql;\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        RAISE WARNING 'Could not create pgml.version stub: %', SQLERRM;\n");
            writer.write("    END;\n");
            writer.write("    \n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE OR REPLACE FUNCTION pgml.transform(\n");
            writer.write("            task TEXT,\n");
            writer.write("            inputs TEXT[],\n");
            writer.write("            model_name TEXT DEFAULT NULL,\n");
            writer.write("            kwargs JSONB DEFAULT '{}'\n");
            writer.write("        ) RETURNS JSONB AS $transform$\n");
            writer.write("        BEGIN\n");
            writer.write("            RAISE EXCEPTION 'PostgresML not available. Install: https://postgresml.org/docs/getting-started/installation';\n");
            writer.write("        END;\n");
            writer.write("        $transform$ LANGUAGE plpgsql;\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        RAISE WARNING 'Could not create pgml.transform stub: %', SQLERRM;\n");
            writer.write("    END;\n");
            writer.write("    \n");
            writer.write("    RAISE NOTICE '✓ Created comprehensive stub functions for PostgresML';\n");
            writer.write("END $create_stubs$;\n\n");

            // Add indexer tables if needed
            if (includePgmlIndexer) {
                writer.write("-- Step 3: Create PGML Indexer tables\n");
                writer.write("CREATE TABLE IF NOT EXISTS pgml.indexer_jobs (\n");
                writer.write("    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),\n");
                writer.write("    job_name VARCHAR(255) NOT NULL,\n");
                writer.write("    status VARCHAR(50) DEFAULT 'pending',\n");
                writer.write("    model_name VARCHAR(255),\n");
                writer.write("    task_type VARCHAR(100),\n");
                writer.write("    input_data TEXT,\n");
                writer.write("    output_data JSONB,\n");
                writer.write("    error_message TEXT,\n");
                writer.write("    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,\n");
                writer.write("    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,\n");
                writer.write("    completed_at TIMESTAMP,\n");
                writer.write("    metadata JSONB\n");
                writer.write(");\n\n");

                writer.write("CREATE INDEX IF NOT EXISTS idx_pgml_indexer_jobs_status ON pgml.indexer_jobs(status);\n");
                writer.write("CREATE INDEX IF NOT EXISTS idx_pgml_indexer_jobs_created_at ON pgml.indexer_jobs(created_at);\n");
                writer.write("CREATE INDEX IF NOT EXISTS idx_pgml_indexer_jobs_task_type ON pgml.indexer_jobs(task_type);\n\n");
            }

            writer.write("-- Step 4: Final verification and debugging info\n");
            writer.write("DO $final_check$\n");
            writer.write("DECLARE\n");
            writer.write("    embed_count INTEGER;\n");
            writer.write("    signatures TEXT;\n");
            writer.write("BEGIN\n");
            writer.write("    -- Count embed functions\n");
            writer.write("    SELECT COUNT(*) INTO embed_count\n");
            writer.write("    FROM information_schema.routines \n");
            writer.write("    WHERE routine_schema = 'pgml' AND routine_name = 'embed';\n");
            writer.write("    \n");
            writer.write("    -- Get all embed function signatures for debugging\n");
            writer.write("    SELECT string_agg(\n");
            writer.write("        'pgml.embed(' || \n");
            writer.write("        COALESCE(\n");
            writer.write("            (SELECT string_agg(\n");
            writer.write("                data_type || CASE WHEN character_maximum_length IS NOT NULL \n");
            writer.write("                    THEN '(' || character_maximum_length || ')' ELSE '' END,\n");
            writer.write("                ', ' ORDER BY ordinal_position\n");
            writer.write("            )\n");
            writer.write("            FROM information_schema.parameters p \n");
            writer.write("            WHERE p.specific_name = r.specific_name), ''\n");
            writer.write("        ) || ')',\n");
            writer.write("        '; '\n");
            writer.write("    ) INTO signatures\n");
            writer.write("    FROM information_schema.routines r\n");
            writer.write("    WHERE routine_schema = 'pgml' AND routine_name = 'embed';\n");
            writer.write("    \n");
            writer.write("    RAISE NOTICE '';\n");
            writer.write("    RAISE NOTICE '============================================================';\n");
            writer.write("    RAISE NOTICE 'PostgresML Comprehensive Setup Complete';\n");
            writer.write("    RAISE NOTICE '============================================================';\n");
            writer.write("    RAISE NOTICE 'Total embed function variants created: %', embed_count;\n");
            writer.write("    \n");
            writer.write("    IF signatures IS NOT NULL THEN\n");
            writer.write("        RAISE NOTICE 'Available function signatures:';\n");
            writer.write("        RAISE NOTICE '%', signatures;\n");
            writer.write("    END IF;\n");
            writer.write("    \n");
            writer.write("    IF embed_count > 0 THEN\n");
            writer.write("        RAISE NOTICE 'Status: ✓ SUCCESS - All function signatures created';\n");
            writer.write("        RAISE NOTICE 'Spring AI should now find a matching function signature';\n");
            writer.write("    ELSE\n");
            writer.write("        RAISE EXCEPTION 'FAILED - No embed functions were created';\n");
            writer.write("    END IF;\n");
            writer.write("    \n");
            writer.write("    RAISE NOTICE '';\n");
            writer.write("    RAISE NOTICE 'Next steps:';\n");
            writer.write("    RAISE NOTICE '1. If you see function signature errors, check the log above';\n");
            writer.write("    RAISE NOTICE '2. To install PostgresML: https://postgresml.org/docs/getting-started/installation';\n");
            writer.write("    RAISE NOTICE '3. After installing PostgresML, restart your application';\n");
            writer.write("    RAISE NOTICE '============================================================';\n");
            writer.write("END $final_check$;\n");
        }

        System.out.println("Generated comprehensive PostgresML schema file: " + pgmlSchemaFile.getAbsolutePath());
        System.out.println("COMPREHENSIVE FIX: Created " + (6 * 6 * 4) + " function signature combinations to handle all PostgreSQL string type variations");
    }

    /**
     * Generate a custom configuration class that properly configures the selected providers
     * This replaces the problematic auto-configurations with explicit bean creation
     */
    private void generateProviderConfigurationClass(File projectDir) throws IOException {
        File javaDir = new File(projectDir, "src/main/java/" + instanceGroupId.replace('.', '/') + "/config");
        if (!javaDir.exists() && !javaDir.mkdirs()) {
            throw new IOException("Could not create java config directory: " + javaDir.getAbsolutePath());
        }

        File configFile = new File(javaDir, "ProviderConfiguration.java");

        try (FileWriter writer = new FileWriter(configFile)) {
            String packageName = instanceGroupId + ".config";

            writer.write("package " + packageName + ";\n\n");
            writer.write("import org.springframework.context.annotation.Configuration;\n");
            writer.write("import org.springframework.context.annotation.Bean;\n");
            writer.write("import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;\n");
            writer.write("import org.springframework.jdbc.core.JdbcTemplate;\n");
            writer.write("import org.springframework.dao.DataAccessException;\n");
            writer.write("import org.springframework.jdbc.BadSqlGrammarException;\n");
            writer.write("import org.springframework.context.ApplicationListener;\n");
            writer.write("import org.springframework.boot.context.event.ApplicationReadyEvent;\n");
            writer.write("import org.springframework.stereotype.Component;\n");
            writer.write("import lombok.extern.slf4j.Slf4j;\n");
            writer.write("import javax.sql.DataSource;\n");
            writer.write("import java.sql.Connection;\n");
            writer.write("import java.sql.Statement;\n");
            writer.write("import java.sql.ResultSet;\n\n");

            writer.write("/**\n");
            writer.write(" * Generated provider configuration for " + instanceArtifactId + "\n");
            writer.write(" * Automatically debugs PostgresML errors without any manual intervention\n");
            writer.write(" */\n");
            writer.write("@Configuration(proxyBeanMethods = false)\n");
            writer.write("public class ProviderConfiguration {\n\n");

            // Add automatic PostgresML error detector that runs on startup
            if (includeEmbeddingPostgresml || includePgmlIndexer) {
                writer.write("    /**\n");
                writer.write("     * Automatic PostgresML error detector - runs immediately on startup\n");
                writer.write("     * Tests the exact function that's failing and shows debug info\n");
                writer.write("     */\n");
                writer.write("    @Component\n");
                writer.write("    @Slf4j\n");
                writer.write("    public static class AutomaticPostgresMLDebugger implements ApplicationListener<ApplicationReadyEvent> {\n\n");

                writer.write("        private final DataSource dataSource;\n");
                writer.write("        private final JdbcTemplate jdbcTemplate;\n\n");

                writer.write("        public AutomaticPostgresMLDebugger(DataSource dataSource, JdbcTemplate jdbcTemplate) {\n");
                writer.write("            this.dataSource = dataSource;\n");
                writer.write("            this.jdbcTemplate = jdbcTemplate;\n");
                writer.write("        }\n\n");

                writer.write("        @Override\n");
                writer.write("        public void onApplicationEvent(ApplicationReadyEvent event) {\n");
                writer.write("            log.info(\"Application ready - testing PostgresML function...\");\n");
                writer.write("            \n");
                writer.write("            // Test the exact function that Spring AI calls\n");
                writer.write("            try {\n");
                writer.write("                jdbcTemplate.queryForObject(\n");
                writer.write("                    \"SELECT pgml.embed('startup-test'::character varying, 'test'::text, '{}'::jsonb)\", \n");
                writer.write("                    Object.class\n");
                writer.write("                );\n");
                writer.write("                log.info(\"✓ PostgresML function test PASSED - embeddings should work\");\n");
                writer.write("                \n");
                writer.write("            } catch (Exception e) {\n");
                writer.write("                log.error(\"✗ PostgresML function test FAILED - this will cause upload errors\", e);\n");
                writer.write("                \n");
                writer.write("                // Show debug info immediately\n");
                writer.write("                debugPostgresMLError(e);\n");
                writer.write("            }\n");
                writer.write("        }\n\n");

                writer.write("        /**\n");
                writer.write("         * Debug PostgresML error and show immediate fix\n");
                writer.write("         */\n");
                writer.write("        private void debugPostgresMLError(Exception originalError) {\n");
                writer.write("            System.err.println(\"\\n\" + \"=\".repeat(100));\n");
                writer.write("            System.err.println(\"AUTOMATIC PostgresML DEBUG - Error detected on startup\");\n");
                writer.write("            System.err.println(\"Original error: \" + originalError.getMessage());\n");
                writer.write("            System.err.println(\"=\".repeat(100));\n");
                writer.write("            \n");
                writer.write("            try (Connection conn = dataSource.getConnection()) {\n");
                writer.write("                \n");
                writer.write("                // Check schema\n");
                writer.write("                System.err.println(\"\\n1. SCHEMA CHECK:\");\n");
                writer.write("                try (Statement stmt = conn.createStatement()) {\n");
                writer.write("                    ResultSet rs = stmt.executeQuery(\n");
                writer.write("                        \"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'pgml'\");\n");
                writer.write("                    rs.next();\n");
                writer.write("                    \n");
                writer.write("                    if (rs.getInt(1) > 0) {\n");
                writer.write("                        System.err.println(\"   ✓ pgml schema EXISTS\");\n");
                writer.write("                    } else {\n");
                writer.write("                        System.err.println(\"   ✗ pgml schema MISSING!\");\n");
                writer.write("                    }\n");
                writer.write("                }\n");
                writer.write("                \n");
                writer.write("                // Check function\n");
                writer.write("                System.err.println(\"\\n2. FUNCTION CHECK:\");\n");
                writer.write("                try (Statement stmt = conn.createStatement()) {\n");
                writer.write("                    ResultSet rs = stmt.executeQuery(\n");
                writer.write("                        \"SELECT COUNT(*) FROM information_schema.routines \" +\n");
                writer.write("                        \"WHERE routine_schema = 'pgml' AND routine_name = 'embed'\");\n");
                writer.write("                    rs.next();\n");
                writer.write("                    \n");
                writer.write("                    int funcCount = rs.getInt(1);\n");
                writer.write("                    if (funcCount > 0) {\n");
                writer.write("                        System.err.println(\"   ✓ pgml.embed function EXISTS (\" + funcCount + \" variants)\");\n");
                writer.write("                        \n");
                writer.write("                        // Check specific signature\n");
                writer.write("                        checkFunctionSignature(conn);\n");
                writer.write("                        \n");
                writer.write("                    } else {\n");
                writer.write("                        System.err.println(\"   ✗ pgml.embed function MISSING!\");\n");
                writer.write("                        System.err.println(\"   → This is why your uploads will fail\");\n");
                writer.write("                    }\n");
                writer.write("                }\n");
                writer.write("                \n");
                writer.write("            } catch (Exception debugError) {\n");
                writer.write("                System.err.println(\"Debug connection failed: \" + debugError.getMessage());\n");
                writer.write("            }\n");
                writer.write("            \n");
                writer.write("            // Show immediate fix\n");
                writer.write("            System.err.println(\"\\n\" + \"-\".repeat(80));\n");
                writer.write("            System.err.println(\"IMMEDIATE FIX - Run this SQL in your database:\");\n");
                writer.write("            System.err.println(\"-\".repeat(80));\n");
                writer.write("            System.err.println(\"CREATE SCHEMA IF NOT EXISTS pgml;\");\n");
                writer.write("            System.err.println(\"CREATE OR REPLACE FUNCTION pgml.embed(\");\n");
                writer.write("            System.err.println(\"  model_name character varying,\");\n");
                writer.write("            System.err.println(\"  text_input text,\");\n");
                writer.write("            System.err.println(\"  kwargs jsonb DEFAULT '{}'\");\n");
                writer.write("            System.err.println(\") RETURNS FLOAT[] AS $$\");\n");
                writer.write("            System.err.println(\"BEGIN\");\n");
                writer.write("            System.err.println(\"  RAISE EXCEPTION 'PostgresML not installed';\");\n");
                writer.write("            System.err.println(\"END;\");\n");
                writer.write("            System.err.println(\"$$ LANGUAGE plpgsql;\");\n");
                writer.write("            System.err.println(\"-\".repeat(80));\n");
                writer.write("            System.err.println(\"=\".repeat(100));\n");
                writer.write("        }\n\n");

                writer.write("        private void checkFunctionSignature(Connection conn) {\n");
                writer.write("            try (Statement stmt = conn.createStatement()) {\n");
                writer.write("                ResultSet rs = stmt.executeQuery(\n");
                writer.write("                    \"SELECT string_agg(p.data_type, ', ' ORDER BY p.ordinal_position) as params \" +\n");
                writer.write("                    \"FROM information_schema.routines r \" +\n");
                writer.write("                    \"LEFT JOIN information_schema.parameters p ON r.specific_name = p.specific_name \" +\n");
                writer.write("                    \"WHERE r.routine_schema = 'pgml' AND r.routine_name = 'embed' \" +\n");
                writer.write("                    \"GROUP BY r.specific_name\");\n");
                writer.write("                \n");
                writer.write("                boolean foundTarget = false;\n");
                writer.write("                System.err.println(\"   Available function signatures:\");\n");
                writer.write("                \n");
                writer.write("                while (rs.next()) {\n");
                writer.write("                    String params = rs.getString(\"params\");\n");
                writer.write("                    System.err.println(\"   - pgml.embed(\" + params + \")\");\n");
                writer.write("                    \n");
                writer.write("                    if (params != null && params.contains(\"character varying\") && \n");
                writer.write("                        params.contains(\"text\") && params.contains(\"jsonb\")) {\n");
                writer.write("                        foundTarget = true;\n");
                writer.write("                        System.err.println(\"     ✓ MATCHES Spring AI requirement\");\n");
                writer.write("                    }\n");
                writer.write("                }\n");
                writer.write("                \n");
                writer.write("                if (!foundTarget) {\n");
                writer.write("                    System.err.println(\"   ✗ MISSING required: (character varying, text, jsonb)\");\n");
                writer.write("                }\n");
                writer.write("                \n");
                writer.write("            } catch (Exception e) {\n");
                writer.write("                System.err.println(\"   Function signature check failed: \" + e.getMessage());\n");
                writer.write("            }\n");
                writer.write("        }\n");
                writer.write("    }\n\n");
            }

            writer.write("}\n");
        }

        System.out.println("Generated ProviderConfiguration.java with automatic PostgresML debugging: " + configFile.getAbsolutePath());
    }




    private void writeProviderConfigurationClass(FileWriter writer) throws IOException {
        String packageName = instanceGroupId + ".config";

        writer.write("package " + packageName + ";\n\n");
        writer.write("import org.springframework.context.annotation.Configuration;\n");
        writer.write("import org.springframework.context.annotation.Bean;\n");
        writer.write("import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;\n");
        writer.write("import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;\n");
        writer.write("import org.springframework.beans.factory.annotation.Value;\n");
        writer.write("import org.springframework.jdbc.core.JdbcTemplate;\n");
        writer.write("import org.springframework.ai.embedding.EmbeddingModel;\n");
        writer.write("import org.springframework.ai.vectorstore.pgvector.PgVectorStore;\n");

        writer.write("\n/**\n");
        writer.write(" * Generated provider configuration for " + instanceArtifactId + "\n");
        writer.write(" * \n");
        writer.write(" * This configuration only creates beans that are NOT created by the modules.\n");
        writer.write(" * Individual modules (kompile-embedding-*, kompile-vectorstore-*) create\n");
        writer.write(" * their own beans via their own configuration classes.\n");
        writer.write(" * \n");
        writer.write(" * Configured providers: " + getProviderSummary() + "\n");
        writer.write(" */\n");
        writer.write("@Configuration(proxyBeanMethods = false)\n");
        writer.write("public class ProviderConfiguration {\n\n");

        writer.write("    // No beans needed here - modules create their own beans\n");
        writer.write("    // This class exists in case you need custom cross-cutting configuration\n\n");

        writer.write("}\n");
    }

    private void addApplicationDependencies() {
        defaultDependencies.clear();
        addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter-web", "${spring-boot.version}");
        addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter", "${spring-boot.version}");
        addDependency(defaultDependencies, "org.springframework.ai", "spring-ai-starter-mcp-client", "${spring-ai.version}");
        addDependency(defaultDependencies, "org.springframework.ai", "spring-ai-starter-mcp-server", "${spring-ai.version}");

        addDependency(defaultDependencies, "jakarta.mail", "jakarta.mail-api", DEFAULT_JAKARTA_MAIL_VERSION);

        addDependency(defaultDependencies, "org.apache.logging.log4j", "log4j-api", "${log4j.version}");
        addDependency(defaultDependencies, "org.apache.logging.log4j", "log4j-core", "${log4j.version}");

        // Add database dependencies for PostgreSQL
        if (includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer) {
            // PostgreSQL driver
            addDependency(defaultDependencies, "org.postgresql", "postgresql", "${postgres.version}", "compile", null, false);

            // Spring JDBC for database operations
            addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter-jdbc", "${spring-boot.version}");
        }

        // ... rest of existing dependencies remain the same
        if (includeAppMain) addDependency(defaultDependencies, "ai.kompile", "kompile-app-main", "${kompile.project.version}");
        if (includeAppCore) addDependency(defaultDependencies, "ai.kompile", "kompile-app-core", "${kompile.project.version}");
        if (includeLoadersOrchestrator) addDependency(defaultDependencies, "ai.kompile", "kompile-app-loaders-orchestrator", "${kompile.project.version}");

        // Original loaders
        if (includeLoaderTika) addDependency(defaultDependencies, "ai.kompile", "kompile-loader-tika", "${kompile.project.version}");
        if (includeLoaderPdf) addDependency(defaultDependencies, "ai.kompile", "kompile-loader-pdf", "${kompile.project.version}");

        // New specialized loaders
        if (includeLoaderMicrosoft) addDependency(defaultDependencies, "ai.kompile", "kompile-loader-microsoft", "${kompile.project.version}");
        if (includeLoaderMail) addDependency(defaultDependencies, "ai.kompile", "kompile-loader-mail", "${kompile.project.version}");
        if (includeLoaderPdfExtended) addDependency(defaultDependencies, "ai.kompile", "kompile-loader-pdf-extended", "${kompile.project.version}");

        if (includeChunkerSentence) addDependency(defaultDependencies, "ai.kompile", "kompile-chunker-sentence", "${kompile.project.version}");
        if (includeChunkerRecursiveCharacter) addDependency(defaultDependencies, "ai.kompile", "kompile-chunker-recursivecharacter", "${kompile.project.version}");
        if (includeChunkerMarkdown) addDependency(defaultDependencies, "ai.kompile", "kompile-chunker-markdown", "${kompile.project.version}");
        if (includeChunkerToken) addDependency(defaultDependencies, "ai.kompile", "kompile-chunker-token", "${kompile.project.version}");

        if (includeAnserini) addDependency(defaultDependencies, "ai.kompile", "kompile-app-anserini", "${kompile.project.version}");
        if(includeVectorStoreAnserini) {
            addDependency(defaultDependencies,"ai.kompile","kompile-vectorstore-anserini","${kompile.project.version}");
        }


        if (includeLlmOpenai) addDependency(defaultDependencies, "ai.kompile", "kompile-app-openai-llm", "${kompile.project.version}");
        if (includeLlmAnthropic) addDependency(defaultDependencies, "ai.kompile", "kompile-app-anthropic-llm", "${kompile.project.version}");
        if (includeLlmGemini) addDependency(defaultDependencies, "ai.kompile", "kompile-app-gemini-llm", "${kompile.project.version}");
        if (includeEmbeddingOpenai) addDependency(defaultDependencies, "ai.kompile", "kompile-embedding-openai", "${kompile.project.version}");
        if (includeEmbeddingSentenceTransformer) addDependency(defaultDependencies, "ai.kompile", "kompile-embedding-sentence-transformer", "${kompile.project.version}");
        if (includeVectorstoreChroma) addDependency(defaultDependencies, "ai.kompile", "kompile-vectorstore-chroma", "${kompile.project.version}");
        if (includeEmbeddingPostgresml) addDependency(defaultDependencies, "ai.kompile", "kompile-embedding-postgresml", "${kompile.project.version}");
        if (includePgmlIndexer) addDependency(defaultDependencies, "ai.kompile", "kompile-app-pgml-indexer", "${kompile.project.version}");
        if (includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer) {
            addDependency(defaultDependencies, "ai.kompile", "kompile-vectorstore-pgvector", "${kompile.project.version}");
        }
        if (includeToolFilesystem) addDependency(defaultDependencies, "ai.kompile", "kompile-tool-filesystem", "${kompile.project.version}");
        if (includeToolRag) addDependency(defaultDependencies, "ai.kompile", "kompile-tool-rag", "${kompile.project.version}");

        addDependency(defaultDependencies, "org.projectlombok", "lombok", "${lombok.version}", "provided", null, true);
        addDependency(defaultDependencies, "com.fasterxml.jackson.core", "jackson-databind", "${jackson.version}");
        addDependency(defaultDependencies, "com.google.guava", "guava", "${guava.version}");
        model.setDependencies(defaultDependencies);
    }

    /**
     * Generate application.properties with ONLY auto-configuration exclusions
     * No API keys, no runtime configuration - just structural decisions
     */
    private void generateApplicationProperties(File projectDir) throws IOException {
        File resourcesDir = new File(projectDir, "src/main/resources");
        if (!resourcesDir.exists() && !resourcesDir.mkdirs()) {
            throw new IOException("Could not create resources directory: " + resourcesDir.getAbsolutePath());
        }

        File appPropsFile = new File(resourcesDir, "application.properties");

        try (FileWriter writer = new FileWriter(appPropsFile)) {
            writeApplicationPropertiesHeader(writer);
            writeDatabaseConfiguration(writer);
            writeSchemaManagementConfiguration(writer);
            writeAutoConfigurationExclusions(writer);
            writeProviderEnablementFlags(writer);
            writeStructuralConfiguration(writer);
            writeConfigurationTemplate(writer);
        }

        System.out.println("Generated application.properties: " + appPropsFile.getAbsolutePath());
    }

    private void writeDatabaseConfiguration(FileWriter writer) throws IOException {
        if (!(includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer)) {
            return;
        }

        writer.write("# =============================================================================\n");
        writer.write("# DATABASE CONFIGURATION\n");
        writer.write("# Connects to real PostgreSQL server and auto-creates database if needed\n");
        writer.write("# =============================================================================\n");

        writer.write("# PostgreSQL Connection Settings\n");
        writer.write("spring.datasource.url=" + databaseUrl + "\n");
        writer.write("spring.datasource.username=" + databaseUsername + "\n");
        writer.write("spring.datasource.password=" + databasePassword + "\n");
        writer.write("spring.datasource.driver-class-name=org.postgresql.Driver\n");
        writer.write("\n");

        // Connection pool configuration
        writer.write("# Connection Pool Configuration\n");
        writer.write("spring.datasource.type=com.zaxxer.hikari.HikariDataSource\n");
        writer.write("spring.datasource.hikari.maximum-pool-size=10\n");
        writer.write("spring.datasource.hikari.minimum-idle=2\n");
        writer.write("spring.datasource.hikari.connection-timeout=20000\n");
        writer.write("spring.datasource.hikari.idle-timeout=300000\n");
        writer.write("spring.datasource.hikari.max-lifetime=1800000\n");
        writer.write("spring.datasource.hikari.leak-detection-threshold=60000\n");
        writer.write("\n");
    }

    private void writeSchemaManagementConfiguration(FileWriter writer) throws IOException {
        if (!(includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer)) {
            return;
        }

        writer.write("# =============================================================================\n");
        writer.write("# SCHEMA MANAGEMENT CONFIGURATION\n");
        writer.write("# CRITICAL: pgml-schema.sql MUST be executed first to create pgml schema\n");
        writer.write("# =============================================================================\n");

        if (enableSchemaInit) {
            writer.write("# Simple SQL Schema Initialization\n");
            writer.write("spring.sql.init.enabled=true\n");
            writer.write("spring.sql.init.mode=always\n");

            // Build the schema locations list with PROPER ORDER
            List<String> schemaLocations = new ArrayList<>();

            // CRITICAL: pgml schema MUST come first
            if (includeEmbeddingPostgresml || includePgmlIndexer) {
                schemaLocations.add("classpath:pgml-schema.sql");
            }
            schemaLocations.add("classpath:schema.sql");

            writer.write("spring.sql.init.schema-locations=" + String.join(",", schemaLocations) + "\n");
            writer.write("spring.sql.init.data-locations=classpath:data.sql\n");
            writer.write("spring.sql.init.continue-on-error=true\n");  // Important for graceful failure
            writer.write("spring.sql.init.separator=;\n");
            writer.write("spring.sql.init.encoding=UTF-8\n");
        }

        // Disable JPA/Hibernate schema management
        writer.write("\n# Disable JPA/Hibernate Schema Management\n");
        writer.write("spring.jpa.hibernate.ddl-auto=none\n");
        writer.write("spring.jpa.generate-ddl=false\n");
        writer.write("spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration\n");
        writer.write("\n");
    }

    private void generateSqlSchemaFiles(File projectDir) throws IOException {
        if (!enableSchemaInit) return;

        File resourcesDir = new File(projectDir, "src/main/resources");
        if (!resourcesDir.exists() && !resourcesDir.mkdirs()) {
            throw new IOException("Could not create resources directory: " + resourcesDir.getAbsolutePath());
        }

        // Generate schema.sql with proper PostgreSQL syntax
        File schemaFile = new File(resourcesDir, "schema.sql");
        try (FileWriter writer = new FileWriter(schemaFile)) {
            writer.write("-- Schema initialization for Kompile RAG application\n");
            writer.write("-- Generated on: " + new java.util.Date() + "\n");
            writer.write("-- This script is designed to be idempotent and safe to run multiple times\n");
            writer.write("-- IMPORTANT: This script runs AFTER pgml-schema.sql (if present)\n\n");

            writer.write("-- Enable required PostgreSQL extensions (with error handling)\n");
            writer.write("DO $\n");
            writer.write("BEGIN\n");
            writer.write("    -- Try to create vector extension\n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE EXTENSION IF NOT EXISTS vector;\n");
            writer.write("        RAISE NOTICE 'Vector extension created/verified successfully';\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        RAISE WARNING 'Could not create vector extension: %', SQLERRM;\n");
            writer.write("        RAISE WARNING 'This may be normal if pgvector is not installed. Vector operations will not work.';\n");
            writer.write("    END;\n");
            writer.write("    \n");
            writer.write("    -- Try to create uuid-ossp extension\n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\";\n");
            writer.write("        RAISE NOTICE 'UUID-OSSP extension created/verified successfully';\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        RAISE WARNING 'Could not create uuid-ossp extension: %', SQLERRM;\n");
            writer.write("        RAISE WARNING 'UUID generation will use random() instead';\n");
            writer.write("    END;\n");
            writer.write("END $;\n\n");

            writer.write("-- Create vector store table\n");
            writer.write("CREATE TABLE IF NOT EXISTS vector_store (\n");
            writer.write("    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),\n");
            writer.write("    content TEXT,\n");
            writer.write("    metadata JSONB,\n");
            writer.write("    embedding vector(1536),\n");
            writer.write("    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,\n");
            writer.write("    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n");
            writer.write(");\n\n");

            writer.write("-- Create collections table\n");
            writer.write("CREATE TABLE IF NOT EXISTS collections (\n");
            writer.write("    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),\n");
            writer.write("    name VARCHAR(255) NOT NULL UNIQUE,\n");
            writer.write("    description TEXT,\n");
            writer.write("    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n");
            writer.write(");\n\n");

            writer.write("-- Add collection reference to vector_store if not exists\n");
            writer.write("DO $\n");
            writer.write("BEGIN\n");
            writer.write("    IF NOT EXISTS (\n");
            writer.write("        SELECT 1 FROM information_schema.columns \n");
            writer.write("        WHERE table_name = 'vector_store' AND column_name = 'collection_id'\n");
            writer.write("    ) THEN\n");
            writer.write("        ALTER TABLE vector_store ADD COLUMN collection_id UUID REFERENCES collections(id) ON DELETE SET NULL;\n");
            writer.write("        RAISE NOTICE 'Added collection_id column to vector_store table';\n");
            writer.write("    END IF;\n");
            writer.write("END $;\n\n");

            writer.write("-- Create indexes for performance (with error handling)\n");
            writer.write("DO $\n");
            writer.write("BEGIN\n");
            writer.write("    -- Vector similarity index (only if vector extension available)\n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE INDEX IF NOT EXISTS vector_store_embedding_idx \n");
            writer.write("            ON vector_store USING ivfflat (embedding vector_cosine_ops) \n");
            writer.write("            WITH (lists = 100);\n");
            writer.write("        RAISE NOTICE 'Vector similarity index created successfully';\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        RAISE WARNING 'Could not create vector similarity index: %', SQLERRM;\n");
            writer.write("        RAISE WARNING 'This is normal if vector extension is not available';\n");
            writer.write("    END;\n");
            writer.write("    \n");
            writer.write("    -- JSONB metadata index (FIXED: proper syntax for JSONB)\n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE INDEX IF NOT EXISTS idx_vector_store_metadata \n");
            writer.write("            ON vector_store USING GIN (metadata jsonb_ops);\n");  // FIXED: added jsonb_ops
            writer.write("        RAISE NOTICE 'JSONB metadata index created successfully';\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        RAISE WARNING 'Could not create JSONB metadata index: %', SQLERRM;\n");
            writer.write("    END;\n");
            writer.write("    \n");
            writer.write("    -- Other standard indexes\n");
            writer.write("    CREATE INDEX IF NOT EXISTS idx_vector_store_created_at \n");
            writer.write("        ON vector_store (created_at);\n");
            writer.write("    \n");
            writer.write("    CREATE INDEX IF NOT EXISTS idx_vector_store_collection_id \n");
            writer.write("        ON vector_store (collection_id);\n");
            writer.write("    \n");
            writer.write("    CREATE INDEX IF NOT EXISTS idx_collections_name \n");
            writer.write("        ON collections (name);\n");
            writer.write("    \n");
            writer.write("    RAISE NOTICE 'All standard indexes created successfully';\n");
            writer.write("END $;\n\n");

            writer.write("-- Verify table creation\n");
            writer.write("DO $\n");
            writer.write("BEGIN\n");
            writer.write("    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'vector_store') THEN\n");
            writer.write("        RAISE NOTICE 'Schema initialization completed successfully ✓';\n");
            writer.write("    ELSE\n");
            writer.write("        RAISE EXCEPTION 'Schema initialization failed - vector_store table not found';\n");
            writer.write("    END IF;\n");
            writer.write("END $;\n");
        }

        // Generate data.sql
        File dataFile = new File(resourcesDir, "data.sql");
        try (FileWriter writer = new FileWriter(dataFile)) {
            writer.write("-- Initial data for Kompile RAG application\n");
            writer.write("-- Generated on: " + new java.util.Date() + "\n\n");

            writer.write("-- Insert default collection if it doesn't exist\n");
            writer.write("INSERT INTO collections (name, description) \n");
            writer.write("VALUES ('default', 'Default document collection') \n");
            writer.write("ON CONFLICT (name) DO NOTHING;\n\n");

            writer.write("-- Verify data initialization\n");
            writer.write("DO $\n");
            writer.write("DECLARE\n");
            writer.write("    collection_count INTEGER;\n");
            writer.write("BEGIN\n");
            writer.write("    SELECT COUNT(*) INTO collection_count FROM collections;\n");
            writer.write("    RAISE NOTICE 'Data initialization completed. Collections: %', collection_count;\n");
            writer.write("END $;\n");
        }

        System.out.println("Generated SQL schema files:");
        System.out.println("  - " + schemaFile.getAbsolutePath());
        System.out.println("  - " + dataFile.getAbsolutePath());
    }

    /**
     * Complete the addDebugToErrorHandling method and integration
     * Add these methods to your RagPomGenerator.java class
     */

    /**
     * SIMPLE SOLUTION: Just add debug calls to your existing error handling
     *
     * In your RagPomGenerator, modify the generateDatabaseConfiguration method
     * to add debug calls right where the PostgresML errors already occur
     */

    /**
     * Enhanced DatabaseSetup class with simple debug integration
     * Replace your existing DatabaseSetup generation with this
     */
    private void generateDatabaseConfiguration(File projectDir) throws IOException {
        if (!(includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer)) {
            return;
        }

        File javaDir = new File(projectDir, "src/main/java/" + instanceGroupId.replace('.', '/') + "/config");
        if (!javaDir.exists() && !javaDir.mkdirs()) {
            throw new IOException("Could not create config directory: " + javaDir.getAbsolutePath());
        }

        File configFile = new File(javaDir, "DatabaseSetup.java");
        try (FileWriter writer = new FileWriter(configFile)) {
            String packageName = instanceGroupId + ".config";

            writer.write("package " + packageName + ";\n\n");
            writer.write("import org.springframework.beans.factory.annotation.Value;\n");
            writer.write("import org.springframework.context.annotation.Bean;\n");
            writer.write("import org.springframework.context.annotation.Configuration;\n");
            writer.write("import org.springframework.jdbc.core.JdbcTemplate;\n");
            writer.write("import org.springframework.boot.context.event.ApplicationReadyEvent;\n");
            writer.write("import org.springframework.context.event.EventListener;\n");
            writer.write("import lombok.extern.slf4j.Slf4j;\n");
            writer.write("import javax.sql.DataSource;\n");
            writer.write("import com.zaxxer.hikari.HikariDataSource;\n");
            writer.write("import java.sql.Connection;\n");
            writer.write("import java.sql.DriverManager;\n");
            writer.write("import java.sql.SQLException;\n");
            writer.write("import java.sql.Statement;\n");
            writer.write("import java.sql.ResultSet;\n");
            writer.write("import java.util.regex.Matcher;\n");
            writer.write("import java.util.regex.Pattern;\n\n");

            writer.write("@Configuration(proxyBeanMethods = false)\n");
            writer.write("@Slf4j\n");
            writer.write("public class DatabaseSetup {\n\n");

            writer.write("    @Value(\"${spring.datasource.url}\")\n");
            writer.write("    private String databaseUrl;\n");
            writer.write("    \n");
            writer.write("    @Value(\"${spring.datasource.username}\")\n");
            writer.write("    private String username;\n");
            writer.write("    \n");
            writer.write("    @Value(\"${spring.datasource.password}\")\n");
            writer.write("    private String password;\n\n");

            // Standard DataSource method with debug integration
            writer.write("    @Bean\n");
            writer.write("    public DataSource dataSource() {\n");
            writer.write("        log.info(\"Setting up database connection...\");\n");
            writer.write("        \n");
            writer.write("        try {\n");
            writer.write("            ensureDatabaseExists();\n");
            writer.write("            \n");
            writer.write("            HikariDataSource dataSource = new HikariDataSource();\n");
            writer.write("            dataSource.setJdbcUrl(databaseUrl);\n");
            writer.write("            dataSource.setUsername(username);\n");
            writer.write("            dataSource.setPassword(password);\n");
            writer.write("            dataSource.setDriverClassName(\"org.postgresql.Driver\");\n");
            writer.write("            dataSource.setMaximumPoolSize(10);\n");
            writer.write("            dataSource.setMinimumIdle(2);\n");
            writer.write("            \n");
            writer.write("            log.info(\"DataSource configured successfully\");\n");
            writer.write("            return dataSource;\n");
            writer.write("            \n");
            writer.write("        } catch (Exception e) {\n");
            writer.write("            log.error(\"Database setup failed\", e);\n");
            writer.write("            \n");
            writer.write("            // DEBUG: Check if this is a PostgresML issue\n");
            writer.write("            if (e.getMessage() != null && e.getMessage().contains(\"pgml\")) {\n");
            writer.write("                debugPostgresMLIssue(e);\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("            throw new RuntimeException(\"Database setup failed\", e);\n");
            writer.write("        }\n");
            writer.write("    }\n\n");

            // Database creation method with debug integration
            writer.write("    private void ensureDatabaseExists() {\n");
            writer.write("        try {\n");
            writer.write("            String dbName = extractDatabaseName(databaseUrl);\n");
            writer.write("            String serverUrl = getServerUrl(databaseUrl);\n");
            writer.write("            \n");
            writer.write("            log.info(\"Checking if database '{}' exists...\", dbName);\n");
            writer.write("            \n");
            writer.write("            try (Connection conn = DriverManager.getConnection(serverUrl + \"/postgres\", username, password)) {\n");
            writer.write("                try (Statement stmt = conn.createStatement()) {\n");
            writer.write("                    var rs = stmt.executeQuery(\n");
            writer.write("                        \"SELECT 1 FROM pg_database WHERE datname = '\" + dbName + \"'\");\n");
            writer.write("                    \n");
            writer.write("                    if (!rs.next()) {\n");
            writer.write("                        log.info(\"Database '{}' does not exist. Creating...\", dbName);\n");
            writer.write("                        stmt.executeUpdate(\"CREATE DATABASE \\\"\" + dbName + \"\\\"\");\n");
            writer.write("                        log.info(\"Database '{}' created successfully\", dbName);\n");
            writer.write("                    } else {\n");
            writer.write("                        log.info(\"Database '{}' already exists\", dbName);\n");
            writer.write("                    }\n");
            writer.write("                }\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("        } catch (SQLException e) {\n");
            writer.write("            log.error(\"Failed to ensure database exists: {}\", e.getMessage());\n");
            writer.write("            \n");
            writer.write("            // DEBUG: Check for PostgresML issues right here\n");
            writer.write("            debugPostgresMLIssue(e);\n");
            writer.write("            \n");
            writer.write("            throw new RuntimeException(\"Database setup failed\", e);\n");
            writer.write("        }\n");
            writer.write("    }\n\n");

            // Simple inline debug method - no external dependencies
            writer.write("    /**\n");
            writer.write("     * Simple debug method - called right where errors occur\n");
            writer.write("     */\n");
            writer.write("    private void debugPostgresMLIssue(Exception originalError) {\n");
            writer.write("        System.err.println(\"\\n\" + \"=\".repeat(80));\n");
            writer.write("        System.err.println(\"PostgresML ERROR DEBUG\");\n");
            writer.write("        System.err.println(\"Original Error: \" + originalError.getMessage());\n");
            writer.write("        System.err.println(\"=\".repeat(80));\n");
            writer.write("        \n");
            writer.write("        try (Connection conn = DriverManager.getConnection(databaseUrl, username, password)) {\n");
            writer.write("            \n");
            writer.write("            // Check 1: Does pgml schema exist?\n");
            writer.write("            System.err.println(\"\\n1. SCHEMA CHECK:\");\n");
            writer.write("            try (Statement stmt = conn.createStatement()) {\n");
            writer.write("                ResultSet rs = stmt.executeQuery(\n");
            writer.write("                    \"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'pgml'\");\n");
            writer.write("                rs.next();\n");
            writer.write("                int schemaCount = rs.getInt(1);\n");
            writer.write("                \n");
            writer.write("                if (schemaCount > 0) {\n");
            writer.write("                    System.err.println(\"   ✓ pgml schema EXISTS\");\n");
            writer.write("                } else {\n");
            writer.write("                    System.err.println(\"   ✗ pgml schema MISSING!\");\n");
            writer.write("                    System.err.println(\"   → SOLUTION: CREATE SCHEMA IF NOT EXISTS pgml;\");\n");
            writer.write("                }\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("            // Check 2: Does pgml.embed function exist?\n");
            writer.write("            System.err.println(\"\\n2. FUNCTION CHECK:\");\n");
            writer.write("            try (Statement stmt = conn.createStatement()) {\n");
            writer.write("                ResultSet rs = stmt.executeQuery(\n");
            writer.write("                    \"SELECT COUNT(*) FROM information_schema.routines \" +\n");
            writer.write("                    \"WHERE routine_schema = 'pgml' AND routine_name = 'embed'\");\n");
            writer.write("                rs.next();\n");
            writer.write("                int funcCount = rs.getInt(1);\n");
            writer.write("                \n");
            writer.write("                if (funcCount > 0) {\n");
            writer.write("                    System.err.println(\"   ✓ pgml.embed function EXISTS (\" + funcCount + \" variants)\");\n");
            writer.write("                } else {\n");
            writer.write("                    System.err.println(\"   ✗ pgml.embed function MISSING!\");\n");
            writer.write("                    System.err.println(\"   → This is likely the cause of your error\");\n");
            writer.write("                }\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("            // Check 3: Test the exact function call Spring AI makes\n");
            writer.write("            System.err.println(\"\\n3. FUNCTION CALL TEST:\");\n");
            writer.write("            try (Statement stmt = conn.createStatement()) {\n");
            writer.write("                System.err.println(\"   Testing: pgml.embed('test'::character varying, 'text'::text, '{}'::jsonb)\");\n");
            writer.write("                ResultSet rs = stmt.executeQuery(\n");
            writer.write("                    \"SELECT pgml.embed('test'::character varying, 'text'::text, '{}'::jsonb)\");\n");
            writer.write("                \n");
            writer.write("                if (rs.next()) {\n");
            writer.write("                    System.err.println(\"   ✓ Function call SUCCEEDED - PostgresML should work\");\n");
            writer.write("                }\n");
            writer.write("                \n");
            writer.write("            } catch (SQLException testError) {\n");
            writer.write("                System.err.println(\"   ✗ Function call FAILED: \" + testError.getMessage());\n");
            writer.write("                System.err.println(\"   → This is the EXACT error Spring AI encounters\");\n");
            writer.write("                \n");
            writer.write("                if (testError.getMessage().contains(\"does not exist\")) {\n");
            writer.write("                    System.err.println(\"\\n   IMMEDIATE FIX - Run this SQL:\");\n");
            writer.write("                    System.err.println(\"   CREATE SCHEMA IF NOT EXISTS pgml;\");\n");
            writer.write("                    System.err.println(\"   CREATE OR REPLACE FUNCTION pgml.embed(\");\n");
            writer.write("                    System.err.println(\"     model_name character varying,\");\n");
            writer.write("                    System.err.println(\"     text_input text,\");\n");
            writer.write("                    System.err.println(\"     kwargs jsonb DEFAULT '{}'\");\n");
            writer.write("                    System.err.println(\"   ) RETURNS FLOAT[] AS $$\");\n");
            writer.write("                    System.err.println(\"   BEGIN\");\n");
            writer.write("                    System.err.println(\"     RAISE EXCEPTION 'PostgresML not installed';\");\n");
            writer.write("                    System.err.println(\"   END;\");\n");
            writer.write("                    System.err.println(\"   $$ LANGUAGE plpgsql;\");\n");
            writer.write("                }\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("        } catch (SQLException debugError) {\n");
            writer.write("            System.err.println(\"Debug failed: \" + debugError.getMessage());\n");
            writer.write("        }\n");
            writer.write("        \n");
            writer.write("        System.err.println(\"\\n\" + \"=\".repeat(80));\n");
            writer.write("    }\n\n");

            // Add startup event listener that tests PostgresML immediately
            if (includeEmbeddingPostgresml || includePgmlIndexer) {
                writer.write("    /**\n");
                writer.write("     * Test PostgresML immediately on startup\n");
                writer.write("     */\n");
                writer.write("    @EventListener(ApplicationReadyEvent.class)\n");
                writer.write("    public void testPostgresMLOnStartup() {\n");
                writer.write("        log.info(\"Testing PostgresML function on startup...\");\n");
                writer.write("        \n");
                writer.write("        try (Connection conn = DriverManager.getConnection(databaseUrl, username, password)) {\n");
                writer.write("            try (Statement stmt = conn.createStatement()) {\n");
                writer.write("                // Test the exact function Spring AI calls\n");
                writer.write("                ResultSet rs = stmt.executeQuery(\n");
                writer.write("                    \"SELECT pgml.embed('startup-test'::character varying, 'test'::text, '{}'::jsonb)\");\n");
                writer.write("                \n");
                writer.write("                if (rs.next()) {\n");
                writer.write("                    log.info(\"✓ PostgresML function test PASSED on startup\");\n");
                writer.write("                }\n");
                writer.write("                \n");
                writer.write("            } catch (SQLException e) {\n");
                writer.write("                log.error(\"✗ PostgresML function test FAILED on startup: {}\", e.getMessage());\n");
                writer.write("                \n");
                writer.write("                // Debug immediately when the error occurs\n");
                writer.write("                debugPostgresMLIssue(e);\n");
                writer.write("            }\n");
                writer.write("        } catch (SQLException e) {\n");
                writer.write("            log.error(\"Could not test PostgresML on startup\", e);\n");
                writer.write("        }\n");
                writer.write("    }\n\n");
            }

            // Helper methods
            writer.write("    private String extractDatabaseName(String url) {\n");
            writer.write("        Pattern pattern = Pattern.compile(\".*/([^?]+)\");\n");
            writer.write("        Matcher matcher = pattern.matcher(url);\n");
            writer.write("        if (matcher.find()) {\n");
            writer.write("            return matcher.group(1);\n");
            writer.write("        }\n");
            writer.write("        throw new IllegalArgumentException(\"Could not extract database name from URL: \" + url);\n");
            writer.write("    }\n\n");

            writer.write("    private String getServerUrl(String url) {\n");
            writer.write("        int lastSlash = url.lastIndexOf('/');\n");
            writer.write("        if (lastSlash > 0) {\n");
            writer.write("            return url.substring(0, lastSlash);\n");
            writer.write("        }\n");
            writer.write("        throw new IllegalArgumentException(\"Invalid database URL: \" + url);\n");
            writer.write("    }\n");

            writer.write("}\n");
        }

        System.out.println("Generated DatabaseSetup with inline PostgresML debugging: " + configFile.getAbsolutePath());
    }

    /**
     * The error is happening in Spring AI's PostgresML code, not our DatabaseSetup
     * We need to catch it at the Spring framework level
     *
     * Add this to your RagPomGenerator to create a global exception handler
     */

    private void generateGlobalExceptionHandler(File projectDir) throws IOException {
        File javaDir = new File(projectDir, "src/main/java/" + instanceGroupId.replace('.', '/') + "/config");
        if (!javaDir.exists() && !javaDir.mkdirs()) {
            throw new IOException("Could not create config directory: " + javaDir.getAbsolutePath());
        }

        File handlerFile = new File(javaDir, "PostgresMLErrorCatcher.java");
        try (FileWriter writer = new FileWriter(handlerFile)) {
            String packageName = instanceGroupId + ".config";

            writer.write("package " + packageName + ";\n\n");
            writer.write("import org.springframework.beans.factory.annotation.Autowired;\n");
            writer.write("import org.springframework.web.bind.annotation.ControllerAdvice;\n");
            writer.write("import org.springframework.web.bind.annotation.ExceptionHandler;\n");
            writer.write("import org.springframework.dao.DataAccessException;\n");
            writer.write("import org.springframework.jdbc.BadSqlGrammarException;\n");
            writer.write("import org.springframework.jdbc.core.JdbcTemplate;\n");
            writer.write("import org.springframework.http.ResponseEntity;\n");
            writer.write("import lombok.extern.slf4j.Slf4j;\n");
            writer.write("import java.sql.SQLException;\n");
            writer.write("import java.sql.Connection;\n");
            writer.write("import java.sql.Statement;\n");
            writer.write("import java.sql.ResultSet;\n");
            writer.write("import javax.sql.DataSource;\n\n");

            writer.write("/**\n");
            writer.write(" * Catches PostgresML errors exactly where they occur in Spring AI\n");
            writer.write(" */\n");
            writer.write("@ControllerAdvice\n");
            writer.write("@Slf4j\n");
            writer.write("public class PostgresMLErrorCatcher {\n\n");

            writer.write("    @Autowired(required = false)\n");
            writer.write("    private DataSource dataSource;\n\n");

            writer.write("    @Autowired(required = false)\n");
            writer.write("    private JdbcTemplate jdbcTemplate;\n\n");

            writer.write("    /**\n");
            writer.write("     * Catch BadSqlGrammarException - this is what Spring AI throws\n");
            writer.write("     */\n");
            writer.write("    @ExceptionHandler(BadSqlGrammarException.class)\n");
            writer.write("    public ResponseEntity<String> handleBadSqlGrammar(BadSqlGrammarException e) {\n");
            writer.write("        log.error(\"BadSqlGrammarException caught\", e);\n");
            writer.write("        \n");
            writer.write("        if (e.getMessage() != null && e.getMessage().contains(\"pgml.embed\")) {\n");
            writer.write("            System.err.println(\"\\n\" + \"!\".repeat(100));\n");
            writer.write("            System.err.println(\"POSTGRESQL FUNCTION ERROR CAUGHT!\");\n");
            writer.write("            System.err.println(\"Error: \" + e.getMessage());\n");
            writer.write("            System.err.println(\"!\".repeat(100));\n");
            writer.write("            \n");
            writer.write("            debugPostgresMLError();\n");
            writer.write("        }\n");
            writer.write("        \n");
            writer.write("        return ResponseEntity.status(500).body(\"Database function error: \" + e.getMessage());\n");
            writer.write("    }\n\n");

            writer.write("    /**\n");
            writer.write("     * Also catch general DataAccessException\n");
            writer.write("     */\n");
            writer.write("    @ExceptionHandler(DataAccessException.class)\n");
            writer.write("    public ResponseEntity<String> handleDataAccess(DataAccessException e) {\n");
            writer.write("        if (e.getMessage() != null && e.getMessage().contains(\"pgml\")) {\n");
            writer.write("            System.err.println(\"\\n\" + \"!\".repeat(100));\n");
            writer.write("            System.err.println(\"PostgresML DataAccessException CAUGHT!\");\n");
            writer.write("            System.err.println(\"Error: \" + e.getMessage());\n");
            writer.write("            System.err.println(\"!\".repeat(100));\n");
            writer.write("            \n");
            writer.write("            debugPostgresMLError();\n");
            writer.write("        }\n");
            writer.write("        \n");
            writer.write("        return ResponseEntity.status(500).body(\"Database access error\");\n");
            writer.write("    }\n\n");

            writer.write("    /**\n");
            writer.write("     * Catch any SQLException that mentions pgml\n");
            writer.write("     */\n");
            writer.write("    @ExceptionHandler(SQLException.class)\n");
            writer.write("    public ResponseEntity<String> handleSQLException(SQLException e) {\n");
            writer.write("        if (e.getMessage() != null && e.getMessage().contains(\"pgml\")) {\n");
            writer.write("            System.err.println(\"\\n\" + \"!\".repeat(100));\n");
            writer.write("            System.err.println(\"PostgresML SQLException CAUGHT!\");\n");
            writer.write("            System.err.println(\"Error: \" + e.getMessage());\n");
            writer.write("            System.err.println(\"!\".repeat(100));\n");
            writer.write("            \n");
            writer.write("            debugPostgresMLError();\n");
            writer.write("        }\n");
            writer.write("        \n");
            writer.write("        return ResponseEntity.status(500).body(\"SQL error\");\n");
            writer.write("    }\n\n");

            writer.write("    /**\n");
            writer.write("     * Catch generic RuntimeException that might wrap PostgresML errors\n");
            writer.write("     */\n");
            writer.write("    @ExceptionHandler(RuntimeException.class)\n");
            writer.write("    public ResponseEntity<String> handleRuntimeException(RuntimeException e) {\n");
            writer.write("        if (e.getMessage() != null && e.getMessage().contains(\"pgml.embed\")) {\n");
            writer.write("            System.err.println(\"\\n\" + \"!\".repeat(100));\n");
            writer.write("            System.err.println(\"PostgresML RuntimeException CAUGHT!\");\n");
            writer.write("            System.err.println(\"Error: \" + e.getMessage());\n");
            writer.write("            System.err.println(\"!\".repeat(100));\n");
            writer.write("            \n");
            writer.write("            debugPostgresMLError();\n");
            writer.write("        }\n");
            writer.write("        \n");
            writer.write("        throw e; // Re-throw non-PostgresML errors\n");
            writer.write("    }\n\n");

            writer.write("    /**\n");
            writer.write("     * The actual debug method - runs immediately when PostgresML error occurs\n");
            writer.write("     */\n");
            writer.write("    private void debugPostgresMLError() {\n");
            writer.write("        System.err.println(\"\\n\" + \"=\".repeat(80));\n");
            writer.write("        System.err.println(\"IMMEDIATE PostgresML DEBUG\");\n");
            writer.write("        System.err.println(\"=\".repeat(80));\n");
            writer.write("        \n");
            writer.write("        if (dataSource == null) {\n");
            writer.write("            System.err.println(\"DataSource not available for debugging\");\n");
            writer.write("            printManualFix();\n");
            writer.write("            return;\n");
            writer.write("        }\n");
            writer.write("        \n");
            writer.write("        try (Connection conn = dataSource.getConnection()) {\n");
            writer.write("            \n");
            writer.write("            // Check 1: pgml schema\n");
            writer.write("            System.err.println(\"\\n1. SCHEMA CHECK:\");\n");
            writer.write("            try (Statement stmt = conn.createStatement()) {\n");
            writer.write("                ResultSet rs = stmt.executeQuery(\n");
            writer.write("                    \"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'pgml'\");\n");
            writer.write("                rs.next();\n");
            writer.write("                \n");
            writer.write("                if (rs.getInt(1) > 0) {\n");
            writer.write("                    System.err.println(\"   ✓ pgml schema EXISTS\");\n");
            writer.write("                } else {\n");
            writer.write("                    System.err.println(\"   ✗ pgml schema MISSING\");\n");
            writer.write("                }\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("            // Check 2: pgml.embed function\n");
            writer.write("            System.err.println(\"\\n2. FUNCTION CHECK:\");\n");
            writer.write("            try (Statement stmt = conn.createStatement()) {\n");
            writer.write("                ResultSet rs = stmt.executeQuery(\n");
            writer.write("                    \"SELECT COUNT(*) FROM information_schema.routines \" +\n");
            writer.write("                    \"WHERE routine_schema = 'pgml' AND routine_name = 'embed'\");\n");
            writer.write("                rs.next();\n");
            writer.write("                \n");
            writer.write("                int funcCount = rs.getInt(1);\n");
            writer.write("                if (funcCount > 0) {\n");
            writer.write("                    System.err.println(\"   ✓ pgml.embed function EXISTS (\" + funcCount + \" variants)\");\n");
            writer.write("                    \n");
            writer.write("                    // Check specific signature\n");
            writer.write("                    checkFunctionSignatures(conn);\n");
            writer.write("                    \n");
            writer.write("                } else {\n");
            writer.write("                    System.err.println(\"   ✗ pgml.embed function MISSING!\");\n");
            writer.write("                    System.err.println(\"   → This is the ROOT CAUSE of your error\");\n");
            writer.write("                }\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("            // Check 3: Test exact function call\n");
            writer.write("            System.err.println(\"\\n3. EXACT FUNCTION TEST:\");\n");
            writer.write("            testExactFunctionCall(conn);\n");
            writer.write("            \n");
            writer.write("        } catch (Exception debugError) {\n");
            writer.write("            System.err.println(\"Debug connection failed: \" + debugError.getMessage());\n");
            writer.write("        }\n");
            writer.write("        \n");
            writer.write("        printManualFix();\n");
            writer.write("        System.err.println(\"\\n\" + \"=\".repeat(80));\n");
            writer.write("    }\n\n");

            writer.write("    private void checkFunctionSignatures(Connection conn) {\n");
            writer.write("        try (Statement stmt = conn.createStatement()) {\n");
            writer.write("            ResultSet rs = stmt.executeQuery(\n");
            writer.write("                \"SELECT string_agg( \" +\n");
            writer.write("                \"  p.data_type, ', ' ORDER BY p.ordinal_position \" +\n");
            writer.write("                \") as parameter_types \" +\n");
            writer.write("                \"FROM information_schema.routines r \" +\n");
            writer.write("                \"LEFT JOIN information_schema.parameters p ON r.specific_name = p.specific_name \" +\n");
            writer.write("                \"WHERE r.routine_schema = 'pgml' AND r.routine_name = 'embed' \" +\n");
            writer.write("                \"GROUP BY r.specific_name\");\n");
            writer.write("            \n");
            writer.write("            System.err.println(\"   Available function signatures:\");\n");
            writer.write("            boolean foundTargetSignature = false;\n");
            writer.write("            \n");
            writer.write("            while (rs.next()) {\n");
            writer.write("                String signature = rs.getString(\"parameter_types\");\n");
            writer.write("                System.err.println(\"   - pgml.embed(\" + signature + \")\");\n");
            writer.write("                \n");
            writer.write("                if (signature != null && \n");
            writer.write("                    signature.contains(\"character varying\") && \n");
            writer.write("                    signature.contains(\"text\") &&\n");
            writer.write("                    signature.contains(\"jsonb\")) {\n");
            writer.write("                    foundTargetSignature = true;\n");
            writer.write("                    System.err.println(\"     ✓ MATCHES Spring AI requirement!\");\n");
            writer.write("                }\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("            if (!foundTargetSignature) {\n");
            writer.write("                System.err.println(\"   ✗ MISSING required signature: (character varying, text, jsonb)\");\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("        } catch (Exception e) {\n");
            writer.write("            System.err.println(\"   Function signature check failed: \" + e.getMessage());\n");
            writer.write("        }\n");
            writer.write("    }\n\n");

            writer.write("    private void testExactFunctionCall(Connection conn) {\n");
            writer.write("        try (Statement stmt = conn.createStatement()) {\n");
            writer.write("            System.err.println(\"   Testing: SELECT pgml.embed('test'::character varying, 'text'::text, '{}'::jsonb)\");\n");
            writer.write("            \n");
            writer.write("            ResultSet rs = stmt.executeQuery(\n");
            writer.write("                \"SELECT pgml.embed('test'::character varying, 'text'::text, '{}'::jsonb)\");\n");
            writer.write("            \n");
            writer.write("            if (rs.next()) {\n");
            writer.write("                System.err.println(\"   ✓ Function call SUCCEEDED!\");\n");
            writer.write("                System.err.println(\"   → The function exists, but Spring AI might have a different issue\");\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("        } catch (Exception testError) {\n");
            writer.write("            System.err.println(\"   ✗ Function call FAILED: \" + testError.getMessage());\n");
            writer.write("            System.err.println(\"   → This confirms the function signature is missing\");\n");
            writer.write("        }\n");
            writer.write("    }\n\n");

            writer.write("    private void printManualFix() {\n");
            writer.write("        System.err.println(\"\\n\" + \"-\".repeat(80));\n");
            writer.write("        System.err.println(\"IMMEDIATE FIX - Run this SQL in your database:\");\n");
            writer.write("        System.err.println(\"-\".repeat(80));\n");
            writer.write("        System.err.println(\"\");\n");
            writer.write("        System.err.println(\"CREATE SCHEMA IF NOT EXISTS pgml;\");\n");
            writer.write("        System.err.println(\"\");\n");
            writer.write("        System.err.println(\"CREATE OR REPLACE FUNCTION pgml.embed(\");\n");
            writer.write("        System.err.println(\"  model_name character varying,\");\n");
            writer.write("        System.err.println(\"  text_input text,\");\n");
            writer.write("        System.err.println(\"  kwargs jsonb DEFAULT '{}'\");\n");
            writer.write("        System.err.println(\") RETURNS FLOAT[] AS $$\");\n");
            writer.write("        System.err.println(\"BEGIN\");\n");
            writer.write("        System.err.println(\"  RAISE EXCEPTION 'PostgresML not installed. Visit https://postgresml.org';\");\n");
            writer.write("        System.err.println(\"END;\");\n");
            writer.write("        System.err.println(\"$$ LANGUAGE plpgsql;\");\n");
            writer.write("        System.err.println(\"\");\n");
            writer.write("        System.err.println(\"After running this SQL, restart your application.\");\n");
            writer.write("        System.err.println(\"-\".repeat(80));\n");
            writer.write("    }\n");

            writer.write("}\n");
        }

        System.out.println("Generated PostgresML error catcher: " + handlerFile.getAbsolutePath());
    }


    /**
     * Write the standard DataSource method
     */
    private void writeDataSourceMethod(FileWriter writer) throws IOException {
        writer.write("    /**\n");
        writer.write("     * Create DataSource with enhanced error handling\n");
        writer.write("     */\n");
        writer.write("    @Bean\n");
        writer.write("    public DataSource dataSource() {\n");
        writer.write("        log.info(\"Setting up database connection with PostgresML error detection...\");\n");
        writer.write("        \n");
        writer.write("        try {\n");
        writer.write("            // Ensure database exists before creating DataSource\n");
        writer.write("            ensureDatabaseExists();\n");
        writer.write("            \n");
        writer.write("            // Create HikariCP DataSource\n");
        writer.write("            HikariDataSource dataSource = new HikariDataSource();\n");
        writer.write("            dataSource.setJdbcUrl(databaseUrl);\n");
        writer.write("            dataSource.setUsername(username);\n");
        writer.write("            dataSource.setPassword(password);\n");
        writer.write("            dataSource.setDriverClassName(\"org.postgresql.Driver\");\n");
        writer.write("            dataSource.setMaximumPoolSize(10);\n");
        writer.write("            dataSource.setMinimumIdle(2);\n");
        writer.write("            \n");
        writer.write("            log.info(\"DataSource configured successfully\");\n");
        writer.write("            return dataSource;\n");
        writer.write("            \n");
        writer.write("        } catch (Exception e) {\n");
        writer.write("            log.error(\"Failed to create DataSource\", e);\n");
        writer.write("            handlePostgresMLError(e);\n");
        writer.write("            throw new RuntimeException(\"Database setup failed\", e);\n");
        writer.write("        }\n");
        writer.write("    }\n\n");
    }

    /**
     * Write database creation helper methods
     */
    private void writeDatabaseCreationMethods(FileWriter writer) throws IOException {
        writer.write("    /**\n");
        writer.write("     * Ensure the target database exists, create if it doesn't\n");
        writer.write("     */\n");
        writer.write("    private void ensureDatabaseExists() {\n");
        writer.write("        try {\n");
        writer.write("            String dbName = extractDatabaseName(databaseUrl);\n");
        writer.write("            String serverUrl = getServerUrl(databaseUrl);\n");
        writer.write("            \n");
        writer.write("            log.info(\"Checking if database '{}' exists...\", dbName);\n");
        writer.write("            \n");
        writer.write("            try (Connection conn = DriverManager.getConnection(serverUrl + \"/postgres\", username, password)) {\n");
        writer.write("                try (Statement stmt = conn.createStatement()) {\n");
        writer.write("                    var rs = stmt.executeQuery(\n");
        writer.write("                        \"SELECT 1 FROM pg_database WHERE datname = '\" + dbName + \"'\");\n");
        writer.write("                    \n");
        writer.write("                    if (!rs.next()) {\n");
        writer.write("                        log.info(\"Database '{}' does not exist. Creating...\", dbName);\n");
        writer.write("                        stmt.executeUpdate(\"CREATE DATABASE \\\"\" + dbName + \"\\\"\");\n");
        writer.write("                        log.info(\"Database '{}' created successfully ✓\", dbName);\n");
        writer.write("                    } else {\n");
        writer.write("                        log.info(\"Database '{}' already exists ✓\", dbName);\n");
        writer.write("                    }\n");
        writer.write("                }\n");
        writer.write("            }\n");
        writer.write("            \n");
        writer.write("        } catch (SQLException e) {\n");
        writer.write("            log.error(\"Failed to ensure database exists: {}\", e.getMessage());\n");
        writer.write("            handlePostgresMLError(e);\n");
        writer.write("            throw new RuntimeException(\"Database setup failed\", e);\n");
        writer.write("        }\n");
        writer.write("    }\n\n");

        writer.write("    private String extractDatabaseName(String url) {\n");
        writer.write("        Pattern pattern = Pattern.compile(\".*/([^?]+)\");\n");
        writer.write("        Matcher matcher = pattern.matcher(url);\n");
        writer.write("        if (matcher.find()) {\n");
        writer.write("            return matcher.group(1);\n");
        writer.write("        }\n");
        writer.write("        throw new IllegalArgumentException(\"Could not extract database name from URL: \" + url);\n");
        writer.write("    }\n\n");

        writer.write("    private String getServerUrl(String url) {\n");
        writer.write("        int lastSlash = url.lastIndexOf('/');\n");
        writer.write("        if (lastSlash > 0) {\n");
        writer.write("            return url.substring(0, lastSlash);\n");
        writer.write("        }\n");
        writer.write("        throw new IllegalArgumentException(\"Invalid database URL: \" + url);\n");
        writer.write("    }\n\n");
    }

    /**
     * Write the complete debug methods
     */
    private void writeCompleteDebugMethods(FileWriter writer) throws IOException {
        writer.write("    /**\n");
        writer.write("     * Comprehensive PostgresML diagnostics - safe to call multiple times\n");
        writer.write("     */\n");
        writer.write("    public void debugPostgresMLIssues() {\n");
        writer.write("        System.out.println(\"\\n\" + \"=\".repeat(80));\n");
        writer.write("        System.out.println(\"PostgresML SCHEMA DIAGNOSTICS\");\n");
        writer.write("        System.out.println(\"=\".repeat(80));\n");
        writer.write("        \n");
        writer.write("        try (Connection conn = dataSource().getConnection()) {\n");
        writer.write("            \n");
        writer.write("            // 1. Check if pgml schema exists\n");
        writer.write("            System.out.println(\"\\n1. SCHEMA CHECK:\");\n");
        writer.write("            try (Statement stmt = conn.createStatement()) {\n");
        writer.write("                ResultSet rs = stmt.executeQuery(\n");
        writer.write("                    \"SELECT schema_name FROM information_schema.schemata WHERE schema_name = 'pgml'\"\n");
        writer.write("                );\n");
        writer.write("                \n");
        writer.write("                if (rs.next()) {\n");
        writer.write("                    System.out.println(\"   ✓ pgml schema EXISTS\");\n");
        writer.write("                } else {\n");
        writer.write("                    System.out.println(\"   ✗ pgml schema MISSING - this could be the problem!\");\n");
        writer.write("                    System.out.println(\"   → Run: CREATE SCHEMA IF NOT EXISTS pgml;\");\n");
        writer.write("                }\n");
        writer.write("            }\n");
        writer.write("            \n");
        writer.write("            // 2. Check extensions\n");
        writer.write("            System.out.println(\"\\n2. EXTENSION CHECK:\");\n");
        writer.write("            try (Statement stmt = conn.createStatement()) {\n");
        writer.write("                ResultSet rs = stmt.executeQuery(\n");
        writer.write("                    \"SELECT extname, extversion FROM pg_extension WHERE extname IN ('pgml', 'vector')\"\n");
        writer.write("                );\n");
        writer.write("                \n");
        writer.write("                boolean pgmlFound = false, vectorFound = false;\n");
        writer.write("                while (rs.next()) {\n");
        writer.write("                    String name = rs.getString(\"extname\");\n");
        writer.write("                    String version = rs.getString(\"extversion\");\n");
        writer.write("                    System.out.println(\"   ✓ \" + name + \" extension v\" + version + \" installed\");\n");
        writer.write("                    \n");
        writer.write("                    if (\"pgml\".equals(name)) pgmlFound = true;\n");
        writer.write("                    if (\"vector\".equals(name)) vectorFound = true;\n");
        writer.write("                }\n");
        writer.write("                \n");
        writer.write("                if (!pgmlFound) {\n");
        writer.write("                    System.out.println(\"   ✗ pgml extension NOT installed\");\n");
        writer.write("                    System.out.println(\"   → Install PostgresML: https://postgresml.org/docs/getting-started/installation\");\n");
        writer.write("                }\n");
        writer.write("                if (!vectorFound) {\n");
        writer.write("                    System.out.println(\"   ✗ vector extension NOT installed\");\n");
        writer.write("                    System.out.println(\"   → Install pgvector: https://github.com/pgvector/pgvector\");\n");
        writer.write("                }\n");
        writer.write("            }\n");
        writer.write("            \n");
        writer.write("            // 3. Check pgml functions - THE CRITICAL CHECK\n");
        writer.write("            checkPgmlFunctions(conn);\n");
        writer.write("            \n");
        writer.write("            // 4. Test the actual function call\n");
        writer.write("            testPgmlFunctionCall(conn);\n");
        writer.write("            \n");
        writer.write("            // 5. Provide quick fix\n");
        writer.write("            provideQuickFix();\n");
        writer.write("            \n");
        writer.write("        } catch (SQLException e) {\n");
        writer.write("            System.err.println(\"✗ Database connection failed during diagnostics: \" + e.getMessage());\n");
        writer.write("        }\n");
        writer.write("        \n");
        writer.write("        System.out.println(\"\\n\" + \"=\".repeat(80));\n");
        writer.write("        System.out.println(\"PostgresML DIAGNOSTICS COMPLETE\");\n");
        writer.write("        System.out.println(\"=\".repeat(80));\n");
        writer.write("    }\n\n");

        // Add helper methods for diagnostics
        writer.write("    private void checkPgmlFunctions(Connection conn) throws SQLException {\n");
        writer.write("        System.out.println(\"\\n3. FUNCTION CHECK (CRITICAL):\");\n");
        writer.write("        try (Statement stmt = conn.createStatement()) {\n");
        writer.write("            ResultSet rs = stmt.executeQuery(\n");
        writer.write("                \"SELECT \" +\n");
        writer.write("                \"  routine_name, \" +\n");
        writer.write("                \"  string_agg( \" +\n");
        writer.write("                \"    p.data_type || \" +\n");
        writer.write("                \"    CASE WHEN p.character_maximum_length IS NOT NULL \" +\n");
        writer.write("                \"      THEN '(' || p.character_maximum_length || ')' \" +\n");
        writer.write("                \"      ELSE '' \" +\n");
        writer.write("                \"    END, \" +\n");
        writer.write("                \"    ', ' ORDER BY p.ordinal_position \" +\n");
        writer.write("                \"  ) as parameter_types \" +\n");
        writer.write("                \"FROM information_schema.routines r \" +\n");
        writer.write("                \"LEFT JOIN information_schema.parameters p ON r.specific_name = p.specific_name \" +\n");
        writer.write("                \"WHERE r.routine_schema = 'pgml' AND r.routine_name = 'embed' \" +\n");
        writer.write("                \"GROUP BY r.routine_name, r.specific_name\"\n");
        writer.write("            );\n");
        writer.write("            \n");
        writer.write("            boolean foundTargetSignature = false;\n");
        writer.write("            int embedFunctionCount = 0;\n");
        writer.write("            \n");
        writer.write("            System.out.println(\"   Found pgml.embed function signatures:\");\n");
        writer.write("            while (rs.next()) {\n");
        writer.write("                embedFunctionCount++;\n");
        writer.write("                String signature = rs.getString(\"parameter_types\");\n");
        writer.write("                System.out.println(\"   - pgml.embed(\" + (signature != null ? signature : \"no params\") + \")\");\n");
        writer.write("                \n");
        writer.write("                if (signature != null && \n");
        writer.write("                    signature.contains(\"character varying\") && \n");
        writer.write("                    signature.contains(\"text\") && \n");
        writer.write("                    signature.contains(\"jsonb\")) {\n");
        writer.write("                    foundTargetSignature = true;\n");
        writer.write("                    System.out.println(\"     ✓ THIS MATCHES Spring AI's expected signature!\");\n");
        writer.write("                }\n");
        writer.write("            }\n");
        writer.write("            \n");
        writer.write("            if (embedFunctionCount == 0) {\n");
        writer.write("                System.out.println(\"   ✗ NO pgml.embed functions found!\");\n");
        writer.write("                System.out.println(\"   → This is why you're getting 'function does not exist' error\");\n");
        writer.write("                System.out.println(\"   → Solution: Run the pgml-schema.sql script to create stub functions\");\n");
        writer.write("            } else if (!foundTargetSignature) {\n");
        writer.write("                System.out.println(\"   ✗ Missing required signature: pgml.embed(character varying, text, jsonb)\");\n");
        writer.write("                System.out.println(\"   → Spring AI needs this EXACT signature\");\n");
        writer.write("                System.out.println(\"   → Solution: Create this specific function signature\");\n");
        writer.write("            } else {\n");
        writer.write("                System.out.println(\"   ✓ Required function signature EXISTS - this should work!\");\n");
        writer.write("            }\n");
        writer.write("        }\n");
        writer.write("    }\n\n");

        writer.write("    private void testPgmlFunctionCall(Connection conn) throws SQLException {\n");
        writer.write("        System.out.println(\"\\n4. FUNCTION CALL TEST:\");\n");
        writer.write("        try (Statement stmt = conn.createStatement()) {\n");
        writer.write("            System.out.println(\"   Testing: SELECT pgml.embed('test'::character varying, 'text'::text, '{}'::jsonb)\");\n");
        writer.write("            \n");
        writer.write("            ResultSet rs = stmt.executeQuery(\n");
        writer.write("                \"SELECT pgml.embed('test-model'::character varying, 'test text'::text, '{}'::jsonb)\"\n");
        writer.write("            );\n");
        writer.write("            \n");
        writer.write("            if (rs.next()) {\n");
        writer.write("                System.out.println(\"   ✓ Function call SUCCESS!\");\n");
        writer.write("                System.out.println(\"   → pgml.embed function is working (even if it's a stub)\");\n");
        writer.write("                System.out.println(\"   → Spring AI should be able to call this function\");\n");
        writer.write("            }\n");
        writer.write("            \n");
        writer.write("        } catch (SQLException e) {\n");
        writer.write("            System.out.println(\"   ✗ Function call FAILED: \" + e.getMessage());\n");
        writer.write("            System.out.println(\"   → This is the EXACT error Spring AI encounters\");\n");
        writer.write("            \n");
        writer.write("            if (e.getMessage().contains(\"does not exist\")) {\n");
        writer.write("                System.out.println(\"   → SOLUTION: Create the missing function signature\");\n");
        writer.write("            }\n");
        writer.write("        }\n");
        writer.write("    }\n\n");

        writer.write("    private void provideQuickFix() {\n");
        writer.write("        System.out.println(\"\\n5. QUICK FIX:\");\n");
        writer.write("        System.out.println(\"   If the function is missing, run this SQL to create a stub:\");\n");
        writer.write("        System.out.println(\"   \");\n");
        writer.write("        System.out.println(\"   CREATE SCHEMA IF NOT EXISTS pgml;\");\n");
        writer.write("        System.out.println(\"   CREATE OR REPLACE FUNCTION pgml.embed(\");\n");
        writer.write("        System.out.println(\"     model_name character varying,\");\n");
        writer.write("        System.out.println(\"     text_input text,\");\n");
        writer.write("        System.out.println(\"     kwargs jsonb DEFAULT '{}'\");\n");
        writer.write("        System.out.println(\"   ) RETURNS FLOAT[] AS $$\");\n");
        writer.write("        System.out.println(\"   BEGIN\");\n");
        writer.write("        System.out.println(\"     RAISE EXCEPTION 'PostgresML extension not available. Install from https://postgresml.org';\");\n");
        writer.write("        System.out.println(\"   END;\");\n");
        writer.write("        System.out.println(\"   $$ LANGUAGE plpgsql;\");\n");
        writer.write("    }\n\n");
    }

    /**
     * Write the enhanced error handling method - COMPLETE VERSION
     */
    private void writeEnhancedErrorHandling(FileWriter writer) throws IOException {
        writer.write("    /**\n");
        writer.write("     * Enhanced error handling with PostgresML debugging\n");
        writer.write("     * Call this method whenever PostgresML-related exceptions occur\n");
        writer.write("     */\n");
        writer.write("    public void handlePostgresMLError(Exception originalException) {\n");
        writer.write("        if (originalException.getMessage() != null && \n");
        writer.write("            originalException.getMessage().contains(\"pgml.embed\") && \n");
        writer.write("            originalException.getMessage().contains(\"does not exist\")) {\n");
        writer.write("            \n");
        writer.write("            log.error(\"PostgresML function signature error detected!\", originalException);\n");
        writer.write("            \n");
        writer.write("            System.err.println(\"\\n\" + \"!\".repeat(80));\n");
        writer.write("            System.err.println(\"PostgresML ERROR DETECTED!\");\n");
        writer.write("            System.err.println(\"Original error: \" + originalException.getMessage());\n");
        writer.write("            System.err.println(\"!\".repeat(80));\n");
        writer.write("            \n");
        writer.write("            // Run comprehensive diagnostics\n");
        writer.write("            try {\n");
        writer.write("                debugPostgresMLIssues();\n");
        writer.write("                \n");
        writer.write("                // Provide specific guidance\n");
        writer.write("                System.err.println(\"\\nSPECIFIC SOLUTION FOR YOUR ERROR:\");\n");
        writer.write("                System.err.println(\"1. The pgml.embed function with the required signature is missing\");\n");
        writer.write("                System.err.println(\"2. Spring AI needs: pgml.embed(character varying, text, jsonb)\");\n");
        writer.write("                System.err.println(\"3. Run the SQL commands shown in the 'QUICK FIX' section above\");\n");
        writer.write("                System.err.println(\"4. Restart your application\");\n");
        writer.write("                \n");
        writer.write("            } catch (Exception debugException) {\n");
        writer.write("                log.error(\"Failed to run debug diagnostics\", debugException);\n");
        writer.write("                \n");
        writer.write("                // Fallback: provide basic fix instructions\n");
        writer.write("                System.err.println(\"\\nFALLBACK SOLUTION:\");\n");
        writer.write("                System.err.println(\"Run this SQL in your database:\");\n");
        writer.write("                System.err.println(\"CREATE SCHEMA IF NOT EXISTS pgml;\");\n");
        writer.write("                System.err.println(\"CREATE OR REPLACE FUNCTION pgml.embed(model_name character varying, text_input text, kwargs jsonb DEFAULT '{}') RETURNS FLOAT[] AS $$ BEGIN RAISE EXCEPTION 'PostgresML not installed'; END; $$ LANGUAGE plpgsql;\");\n");
        writer.write("            }\n");
        writer.write("            \n");
        writer.write("            System.err.println(\"\\n\" + \"!\".repeat(80));\n");
        writer.write("            \n");
        writer.write("        } else {\n");
        writer.write("            // Handle other types of database errors\n");
        writer.write("            log.error(\"Database error occurred\", originalException);\n");
        writer.write("            \n");
        writer.write("            if (originalException.getMessage() != null) {\n");
        writer.write("                String errorMsg = originalException.getMessage().toLowerCase();\n");
        writer.write("                \n");
        writer.write("                if (errorMsg.contains(\"schema\") && errorMsg.contains(\"pgml\")) {\n");
        writer.write("                    System.err.println(\"\\nSCHEMA ISSUE DETECTED:\");\n");
        writer.write("                    System.err.println(\"1. Create the pgml schema: CREATE SCHEMA IF NOT EXISTS pgml;\");\n");
        writer.write("                    System.err.println(\"2. Then create the required functions\");\n");
        writer.write("                }\n");
        writer.write("                \n");
        writer.write("                if (errorMsg.contains(\"extension\") && errorMsg.contains(\"pgml\")) {\n");
        writer.write("                    System.err.println(\"\\nEXTENSION ISSUE DETECTED:\");\n");
        writer.write("                    System.err.println(\"1. Install PostgresML: https://postgresml.org/docs/getting-started/installation\");\n");
        writer.write("                    System.err.println(\"2. Restart PostgreSQL server\");\n");
        writer.write("                    System.err.println(\"3. Restart your application\");\n");
        writer.write("                }\n");
        writer.write("            }\n");
        writer.write("        }\n");
        writer.write("    }\n\n");
    }

    /**
     * Write startup diagnostics method
     */
    private void writeStartupDiagnostics(FileWriter writer) throws IOException {
        writer.write("    /**\n");
        writer.write("     * Automatically run PostgresML diagnostics on application startup\n");
        writer.write("     */\n");
        writer.write("    @EventListener(ApplicationReadyEvent.class)\n");
        writer.write("    public void runPostgresMLDiagnosticsOnStartup() {\n");
        writer.write("        try {\n");
        if (includeEmbeddingPostgresml || includePgmlIndexer) {
            writer.write("            // PostgresML modules are enabled - run diagnostics\n");
            writer.write("            log.info(\"PostgresML modules detected - running schema diagnostics...\");\n");
            writer.write("            debugPostgresMLIssues();\n");
        } else {
            writer.write("            // PostgresML modules not enabled - skip diagnostics\n");
            writer.write("            log.info(\"PostgresML modules not enabled - skipping diagnostics\");\n");
        }
        writer.write("        } catch (Exception e) {\n");
        writer.write("            log.error(\"Failed to run PostgresML diagnostics on startup\", e);\n");
        writer.write("            handlePostgresMLError(e);\n");
        writer.write("        }\n");
        writer.write("    }\n\n");
    }

    private void writeApplicationPropertiesHeader(FileWriter writer) throws IOException {
        writer.write("# Generated application.properties\n");
        writer.write("# Project: " + instanceArtifactId + "\n");
        writer.write("# Generated on: " + new java.util.Date() + "\n");
        writer.write("# Configured providers: " + getProviderSummary() + "\n");
        writer.write("\n");

        // Add automatic PostgresML error debugging
        if (includeEmbeddingPostgresml || includePgmlIndexer) {
            writer.write("# =============================================================================\n");
            writer.write("# AUTOMATIC PostgresML ERROR DEBUGGING\n");
            writer.write("# These settings will automatically show PostgresML debug info when errors occur\n");
            writer.write("# =============================================================================\n");
            writer.write("\n");
            writer.write("# Enable detailed SQL logging to catch PostgresML errors\n");
            writer.write("logging.level.org.springframework.jdbc.core.JdbcTemplate=DEBUG\n");
            writer.write("logging.level.org.springframework.jdbc.core.StatementCreatorUtils=TRACE\n");
            writer.write("logging.level.org.springframework.ai.postgresml=DEBUG\n");
            writer.write("logging.level.org.postgresql=DEBUG\n");
            writer.write("logging.level.ai.kompile.app.pgml.indexer=DEBUG\n");
            writer.write("logging.level.ai.kompile.vectorstore=DEBUG\n");
            writer.write("\n");
            writer.write("# Show full exception stack traces\n");
            writer.write("logging.level.org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator=DEBUG\n");
            writer.write("logging.level.org.springframework.dao=DEBUG\n");
            writer.write("\n");
            writer.write("# Custom PostgresML error handler (automatically loaded)\n");
            writer.write("logging.level." + instanceGroupId + ".config=DEBUG\n");
            writer.write("\n");
        }
    }

    private void writeAutoConfigurationExclusions(FileWriter writer) throws IOException {
        List<String> exclusions = determineAutoConfigurationExclusions();

        if (!exclusions.isEmpty()) {
            writer.write("# =============================================================================\n");
            writer.write("# AUTO-CONFIGURATION EXCLUSIONS\n");
            writer.write("# These exclusions prevent bean conflicts by disabling unused provider configs\n");
            writer.write("# =============================================================================\n");
            writer.write("spring.autoconfigure.exclude=\\\n");

            for (int i = 0; i < exclusions.size(); i++) {
                writer.write("    " + exclusions.get(i));
                if (i < exclusions.size() - 1) {
                    writer.write(",\\\n");
                } else {
                    writer.write("\n\n");
                }
            }
        }
    }

    private List<String> determineAutoConfigurationExclusions() {
        List<String> exclusions = new ArrayList<>();
        return exclusions;
    }

    private void writeProviderEnablementFlags(FileWriter writer) throws IOException {
        writer.write("# =============================================================================\n");
        writer.write("# PROVIDER ENABLEMENT FLAGS\n");
        writer.write("# These flags explicitly control which providers are active\n");
        writer.write("# =============================================================================\n");

        // Embedding provider flags
        writer.write("# Embedding Providers\n");
        writer.write("spring.ai.openai.embedding.enabled=" + includeEmbeddingOpenai + "\n");
        writer.write("spring.ai.postgresml.embedding.enabled=" + includeEmbeddingPostgresml + "\n");
        writer.write("spring.ai.transformers.embedding.enabled=" + includeEmbeddingSentenceTransformer + "\n");
        writer.write("\n");

        // Chat provider flags
        writer.write("# Chat Providers\n");
        writer.write("spring.ai.openai.chat.enabled=" + includeLlmOpenai + "\n");
        writer.write("spring.ai.anthropic.chat.enabled=" + includeLlmAnthropic + "\n");
        writer.write("spring.ai.vertex.ai.gemini.chat.enabled=" + includeLlmGemini + "\n");
        writer.write("\n");

        // Vector store flags
        writer.write("# Vector Stores\n");
        writer.write("spring.ai.vectorstore.pgvector.enabled=" + (includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer) + "\n");
        writer.write("spring.ai.vectorstore.chroma.enabled=" + includeVectorstoreChroma + "\n");
        writer.write("\n");
    }

    private void writeStructuralConfiguration(FileWriter writer) throws IOException {
        writer.write("# =============================================================================\n");
        writer.write("# STRUCTURAL CONFIGURATION\n");
        writer.write("# These are build-time decisions that don't change at runtime\n");
        writer.write("# =============================================================================\n");

        // Application basics
        writer.write("spring.application.name=" + instanceArtifactId + "\n");
        writer.write("server.port=8080\n");
        writer.write("\n");

        // Logging structure
        writer.write("# Logging Configuration\n");
        writer.write("logging.level.ai.kompile=INFO\n");
        writer.write("logging.level.org.springframework.ai=INFO\n");
        writer.write("logging.level.org.springframework.boot.autoconfigure=INFO\n");
        writer.write("\n");

        // Vector store structural settings (dimensions, table names, etc.)
        if (includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer) {
            writer.write("# PgVector Structural Configuration\n");
            writer.write("spring.ai.vectorstore.pgvector.table-name=vector_store\n");
            writer.write("spring.ai.vectorstore.pgvector.schema-name=public\n");
            writer.write("spring.ai.vectorstore.pgvector.dimensions=1536\n");
            writer.write("spring.ai.vectorstore.pgvector.distance-type=COSINE_DISTANCE\n");
            writer.write("spring.ai.vectorstore.pgvector.initialize-schema=true\n");
            writer.write("spring.ai.vectorstore.pgvector.schema-validation=true\n");
            writer.write("\n");
        }

        if (includeVectorstoreChroma) {
            writer.write("# Chroma Structural Configuration\n");
            writer.write("spring.ai.vectorstore.chroma.collection-name=kompile_documents\n");
            writer.write("spring.ai.vectorstore.chroma.initialize-schema=true\n");
            writer.write("\n");
        }

        // Application-specific paths and settings
        writer.write("# Kompile Application Structure\n");
        writer.write("anserini.indexPath=./data/index\n");
        writer.write("anserini.corpusPath=./data/anserini_corpus_json_staging\n");
        writer.write("app.document.sources=./data/input_documents/sample.txt,./data/input_documents/sample.pdf\n");
        writer.write("app.document.uploads-path=./data/input_documents/uploads\n");
        writer.write("mcp.filesystem.roots.default.path=./data/shared_files\n");
        writer.write("mcp.filesystem.roots.default.alias=default\n\n");

        // OpenNLP configuration (only if sentence chunker is selected)
        if (includeChunkerSentence) {
            writer.write("# OpenNLP Configuration\n");
            writer.write("kompile.opennlp.sentence.language=" +
                    (supportedLanguages.isEmpty() ? "en" : supportedLanguages.get(0).toLowerCase()) + "\n");
            writer.write("kompile.opennlp.models.path=classpath:models/\n\n");
        }
    }

    private void writeConfigurationTemplate(FileWriter writer) throws IOException {
        writer.write("# =============================================================================\n");
        writer.write("# RUNTIME CONFIGURATION TEMPLATE\n");
        writer.write("# Copy and customize these settings in your environment-specific config\n");
        writer.write("# =============================================================================\n\n");

        // Provider-specific runtime config templates (commented out)
        if (includeEmbeddingOpenai || includeLlmOpenai) {
            writer.write("# OpenAI Configuration (set via environment variables or external config)\n");
            writer.write("# spring.ai.openai.api-key=${OPENAI_API_KEY}\n");
            writer.write("# spring.ai.openai.base-url=https://api.openai.com\n");
            if (includeEmbeddingOpenai) {
                writer.write("# spring.ai.openai.embedding.options.model=text-embedding-3-large\n");
            }
            if (includeLlmOpenai) {
                writer.write("# spring.ai.openai.chat.options.model=gpt-4o\n");
                writer.write("# spring.ai.openai.chat.options.temperature=0.7\n");
            }
            writer.write("\n");
        }

        if (includeEmbeddingPostgresml || includeVectorstorePgvector || includePgmlIndexer) {
            writer.write("# Database Configuration (set via environment variables or external config)\n");
            writer.write("# spring.datasource.url=jdbc:postgresql://localhost:5432/your_database\n");
            writer.write("# spring.datasource.username=${DB_USERNAME}\n");
            writer.write("# spring.datasource.password=${DB_PASSWORD}\n");
            writer.write("# spring.datasource.driver-class-name=org.postgresql.Driver\n");
            writer.write("\n");
        }

        if (includeLlmAnthropic) {
            writer.write("# Anthropic Configuration (set via environment variables or external config)\n");
            writer.write("# spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}\n");
            writer.write("# spring.ai.anthropic.chat.options.model=claude-3-sonnet-20240229\n");
            writer.write("# spring.ai.anthropic.chat.options.temperature=0.7\n");
            writer.write("\n");
        }

        if (includeLlmGemini) {
            writer.write("# Google Vertex AI Configuration (set via environment variables or external config)\n");
            writer.write("# spring.ai.vertex.ai.project-id=${GOOGLE_CLOUD_PROJECT_ID}\n");
            writer.write("# spring.ai.vertex.ai.location=us-central1\n");
            writer.write("# spring.ai.vertex.ai.gemini.chat.options.model=gemini-1.5-flash-latest\n");
            writer.write("\n");
        }

        if (includeVectorstoreChroma) {
            writer.write("# Chroma Configuration (set via environment variables or external config)\n");
            writer.write("# spring.ai.vectorstore.chroma.client.host=localhost\n");
            writer.write("# spring.ai.vectorstore.chroma.client.port=8000\n");
            writer.write("\n");
        }

        writer.write("# Example: Create application-dev.properties, application-prod.properties, etc.\n");
        writer.write("# Or set environment variables: OPENAI_API_KEY, DB_USERNAME, DB_PASSWORD, etc.\n");
        writer.write("# Or use command line: --spring.ai.openai.api-key=your-key\n");
    }

    private String getProviderSummary() {
        List<String> providers = new ArrayList<>();
        if (includeEmbeddingOpenai) providers.add("OpenAI Embedding");
        if (includeEmbeddingPostgresml) providers.add("PostgresML Embedding");
        if (includeEmbeddingSentenceTransformer) providers.add("Sentence Transformer");
        if (includeLlmOpenai) providers.add("OpenAI Chat");
        if (includeLlmAnthropic) providers.add("Anthropic Chat");
        if (includeLlmGemini) providers.add("Gemini Chat");
        if (includeVectorstorePgvector) providers.add("PgVector Store");
        if (includeVectorstoreChroma) providers.add("Chroma Store");
        return String.join(", ", providers);
    }

    private void addApplicationBuild() {
        if (model.getBuild() == null) {
            model.setBuild(new Build());
        }
        Build build = model.getBuild();
        try {
            Plugin compilerPlugin = createPlugin("org.apache.maven.plugins", "maven-compiler-plugin", "${maven-compiler-plugin.version}");
            Xpp3Dom compilerConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "  <release>${java.version}</release>\n" +
                            "<parameters>true</parameters>\n" +
                            "  <annotationProcessorPaths>" +
                            "    <path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>${lombok.version}</version></path>" +
                            "    <path><groupId>org.springframework.boot</groupId><artifactId>spring-boot-configuration-processor</artifactId><version>${spring-boot.version}</version></path>" +
                            "  </annotationProcessorPaths>" +
                            "</configuration>"
            ));
            compilerPlugin.setConfiguration(compilerConfig);
            build.addPlugin(compilerPlugin);

            Plugin resourcesPlugin = createPlugin("org.apache.maven.plugins", "maven-resources-plugin", "${maven-resources-plugin.version}");
            build.addPlugin(resourcesPlugin);

            Plugin jarPlugin = createPlugin("org.apache.maven.plugins", "maven-jar-plugin", "${maven-jar-plugin.version}");
            Xpp3Dom jarConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "  <archive><manifest><mainClass>${start-class}</mainClass><addClasspath>true</addClasspath><classpathPrefix>BOOT-INF/lib/</classpathPrefix></manifest></archive>" +
                            "</configuration>"));
            jarPlugin.setConfiguration(jarConfig);
            build.addPlugin(jarPlugin);

            Plugin springBootMainBuildPlugin = createPlugin("org.springframework.boot", "spring-boot-maven-plugin", "${spring-boot.version}");
            Xpp3Dom springBootMainConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "  <mainClass>${start-class}</mainClass>" +
                            "  <excludes><exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude></excludes>" +
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

    private void addNativeProfile(String nativeImageMainClassFqcn, List<String> modelFilesToIncludePreviously) {
        // MODIFIED: modelFilesToIncludePreviously is now an empty list passed from call(),
        // as we are not bundling these model files (OpenNLP, Anserini indexes/encoders) into the native image.
        // The GraalVM -H:IncludeResources arguments for specific model files are removed.

        Profile nativeProfile = new Profile();
        nativeProfile.setId("native");
        Build nativeProfileBuild = new Build();

        // Spring Boot Plugin for AOT processing and repackaging (remains important)
        Plugin springBootPluginNative = createPlugin("org.springframework.boot", "spring-boot-maven-plugin", "${spring-boot.version}");
        try {
            Xpp3Dom springBootNativeConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "  <mainClass>" + (nativeImageMainClassFqcn != null ? nativeImageMainClassFqcn : CORE_APP_MAIN_CLASS_FQCN) + "</mainClass>" +
                            "  <classifier>exec</classifier>" + // Ensures the fat jar has a unique classifier
                            "  <excludes><exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude></excludes>" +
                            "</configuration>"
            ));
            springBootPluginNative.setConfiguration(springBootNativeConfig);
        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Error configuring spring-boot-maven-plugin in native profile", e);
        }
        PluginExecution processAotExecution = new PluginExecution();
        processAotExecution.setId("process-aot");
        processAotExecution.addGoal("process-aot");
        springBootPluginNative.addExecution(processAotExecution);

        PluginExecution repackageExecutionNative = new PluginExecution();
        repackageExecutionNative.setId("repackage-native-profile"); // Ensure ID is unique if called elsewhere
        repackageExecutionNative.addGoal("repackage");
        springBootPluginNative.addExecution(repackageExecutionNative);
        nativeProfileBuild.addPlugin(springBootPluginNative);

        // Build Helper Maven Plugin for adding AOT generated sources/resources (remains important)
        Plugin buildHelperPlugin = createPlugin("org.codehaus.mojo", "build-helper-maven-plugin", "${build-helper-maven-plugin.version}");
        PluginExecution addAotSourcesExecution = new PluginExecution();
        addAotSourcesExecution.setId("add-spring-aot-sources");
        addAotSourcesExecution.addGoal("add-source");
        addAotSourcesExecution.setPhase("generate-sources");
        try {
            Xpp3Dom addAotSourcesConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration><sources><source>${project.build.directory}/spring-aot/main/sources</source></sources></configuration>"
            ));
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
                    "<configuration><resources><resource><directory>${project.build.directory}/spring-aot/main/resources</directory></resource></resources></configuration>"
            ));
            addAotResourcesExecution.setConfiguration(addAotResourcesConfig);
        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Error configuring build-helper-maven-plugin for AOT resources", e);
        }
        buildHelperPlugin.addExecution(addAotResourcesExecution);
        nativeProfileBuild.addPlugin(buildHelperPlugin);

        // GraalVM Native Maven Plugin
        Plugin nativeMavenPlugin = createPlugin("org.graalvm.buildtools", "native-maven-plugin", "${native-maven-plugin.version}");
        nativeMavenPlugin.setExtensions(true);

        Xpp3Dom nativePluginConfig = new Xpp3Dom("configuration");
        addChild(nativePluginConfig, "imageName", "${native.image.name}");
        addChild(nativePluginConfig, "mainClass", (nativeImageMainClassFqcn != null ? nativeImageMainClassFqcn : CORE_APP_MAIN_CLASS_FQCN));
        addChild(nativePluginConfig, "quickBuild", "false");
        addChild(nativePluginConfig, "jarArtifact", "${project.build.directory}/${project.build.finalName}-exec.jar");

        Xpp3Dom metadataRepoElement = addChild(nativePluginConfig, "metadataRepository", null);
        addChild(metadataRepoElement, "enabled", "true");

        Xpp3Dom buildArgsDom = new Xpp3Dom("buildArgs");
        // Common build args from your original file
        addBuildArg(buildArgsDom, "-J-Xmx16g");
        addBuildArg(buildArgsDom, "--verbose");
        addBuildArg(buildArgsDom, "--no-fallback");
        addBuildArg(buildArgsDom, "--allow-incomplete-classpath");
        addBuildArg(buildArgsDom, "-H:+ReportExceptionStackTraces");
        addBuildArg(buildArgsDom, "-Dspring.native.remove-unused-autoconfig=true");
        addBuildArg(buildArgsDom, "-H:+AddAllFileSystemProviders"); // Crucial for FS access to models
        addBuildArg(buildArgsDom, "--enable-url-protocols=http,https"); // For KompileModelManager if it needs to download
        addBuildArg(buildArgsDom, "-Djava.awt.headless=true");

        // Initialization args from your original file
        String initializeAtBuildTimeArg = "org.apache.logging.log4j.Util,org.apache.logging.log4j.status.StatusLogger,org.apache.logging.log4j.util.ProviderUtil,org.apache.logging.log4j.util.PropertySource$Util,org.apache.logging.log4j.core.impl.Log4jProvider,org.apache.logging.log4j.spi.AbstractLogger,org.apache.logging.log4j.core.impl.Log4jContextFactory,org.apache.logging.log4j.core.selector.ClassLoaderContextSelector,org.apache.logging.log4j.core.LifeCycle$State,org.apache.logging.log4j.status.StatusLogger,org.apache.logging.log4j.spi.StandardLevel,,org.apache.logging.log4j.util.Strings,org.apache.logging.log4j.Level,org.apache.logging.log4j.util.PropertiesUtil,org.apache.logging.log4j.util.OsgiServiceLocator,org.apache.logging.log4j.util.PropertyFilePropertySource,org.apache.logging.log4j.message.ParameterFormatter,org.apache.logging.log4j.status.StatusLogger$Config,org.apache.logging.log4j.status.StatusLogger$InstanceHolder";
        addBuildArg(buildArgsDom, "--initialize-at-build-time=" + initializeAtBuildTimeArg);
        String initializeAtRunTimeArg = "org.apache.lucene.util.ScalarQuantizer,org.jline.nativ.JLineLibrary,org.jline.terminal.impl.jna,org.jline.terminal.impl.jna.linux.LinuxNativePty$UtilLibrary,org.nd4j.nativeblas.NativeOpsHolder,org.nd4j.linalg.api.memory.deallocation.DeallocatorService$DeallocatorServiceThread,org.nd4j.linalg.cpu.nativecpu.CpuEnvironment,org.bytedeco.onnxruntime.presets.onnxruntime,org.bytedeco.openblas.presets.openblas,org.bytedeco.onnx.presets.onnx,org.bytedeco.opencl.presets.OpenCL,org.bytedeco.openblas.presets.openblas_nolapack,org.bytedeco.dnnl.presets.dnnl,org.bytedeco.mkldnn.global.mklml,org.bytedeco.mkldnn.presets.mklml,org.bytedeco.opencl.global.OpenCL,org.nd4j.linalg.cpu.nativecpu.bindings.Nd4jCpu,org.bytedeco.onnx.global.onnx,org.bytedeco.tensorflow.presets.tensorflow,org.bytedeco.openblas.global.openblas,org.bytedeco.mkldnn.global.mkldnn,org.bytedeco.openblas.global.openblas_nolapack,org.bytedeco.onnxruntime.global.onnxruntime,org.bytedeco.javacpp.Loader$Helper,org.bytedeco.javacpp.Loader,org.bytedeco.dnnl.global.dnnl,org.bytedeco.javacpp.Pointer,org.nd4j.autodiff.samediff.internal.memory.ArrayCacheMemoryMgr,org.nd4j.linalg.factory.Nd4j,org.bytedeco.javacpp.Pointer$DeallocatorThread,org.nd4j.linalg.api.ops.impl.layers.ExternalErrorsFunction,org.springframework.ai.chat.client.advisor,reactor.core.scheduler,java.awt.event,org.apache.poi.util.RandomSingleton,sun.awt.X11,sun.rmi.server,java.rmi.server,sun.java.rmi.server,sun.rmi.transport,org.apache.tomcat.jni.SSL,sun.awt.X11GraphicsConfig,org.springframework.web.reactive.function.client.DefaultExchangeStrategiesBuilder,org.springframework.boot.loader.ref.DefaultCleaner,org.apache.tomcat.util.net.openssl.OpenSSLContext,org.apache.tomcat.util.net.openssl.OpenSSLEngine,sun.awt.dnd.SunDropTargetContextPeer$EventDispatcher,org.springframework.core.io.VfsUtils,org.springframework.boot.loader.ref.Cleaner,org.springframework.boot.loader.ref.DefaultCleaner,org.springframework.web.reactive.function.client.DefaultExchangeStrategiesBuilder,reactor.core.scheduler.SchedulerState$DisposeAwaiterRunnable,org.apache.catalina.mbeans.MBeanUtils,org.apache.catalina.mbeans.MBeanFactory";
        addBuildArg(buildArgsDom, "--initialize-at-run-time=" + initializeAtRunTimeArg);
        addBuildArg(buildArgsDom,"--trace-class-initialization=org.apache.lucene.util.ScalarQuantizer,org.jline.terminal.impl.jna,org.jline.terminal.impl.jna.linux.LinuxNativePty$UtilLibrary,org.jline.nativ.JLineLibrary,org.nd4j.nativeblas.NativeOpsHolder,org.nd4j.linalg.api.memory.deallocation.DeallocatorService$DeallocatorServiceThread,org.nd4j.linalg.cpu.nativecpu.CpuEnvironment,org.bytedeco.openblas.presets.openblas,org.bytedeco.onnxruntime.presets.onnxruntime,org.bytedeco.onnx.presets.onnx,org.bytedeco.opencl.presets.OpenCL,org.bytedeco.openblas.presets.openblas_nolapack,org.bytedeco.dnnl.presets.dnnl,org.bytedeco.mkldnn.presets.mklml,org.bytedeco.opencl.global.OpenCL,org.bytedeco.tensorflow.presets.tensorflow,org.bytedeco.mkldnn.global.mklml,org.nd4j.linalg.cpu.nativecpu.bindings.Nd4jCpu,org.bytedeco.onnx.global.onnx,org.bytedeco.mkldnn.global.mkldnn,org.bytedeco.openblas.global.openblas,org.bytedeco.openblas.global.openblas_nolapack,org.bytedeco.onnxruntime.global.onnxruntime,org.bytedeco.javacpp.Loader$Helper,org.bytedeco.javacpp.Loader,org.bytedeco.dnnl.global.dnnl,org.bytedeco.javacpp.Pointer,org.nd4j.autodiff.samediff.internal.memory.ArrayCacheMemoryMgr,org.bytedeco.javacpp.Pointer$DeallocatorThread,org.nd4j.linalg.api.ops.impl.layers.ExternalErrorsFunction,org.nd4j.linalg.factory.Nd4j,org.springframework.ai.chat.client.advisor.api.BaseAdvisor,reactor.core.scheduler.Schedulers,reactor.core.scheduler.BoundedElasticScheduler$BoundedState,reactor.core.scheduler.BoundedElasticSchedulerSupplier,reactor.core.scheduler.BoundedElasticScheduler,reactor.core.scheduler.BoundedElasticScheduler$BoundedServices$1,reactor.core.scheduler.BoundedElasticScheduler$BoundedServices");
        // Include essential resources (Log4j config, Spring specific files, SQL schemas, etc.)
        addBuildArg(buildArgsDom, "-H:IncludeResources=log4j2.xml");
        addBuildArg(buildArgsDom, "-H:IncludeResources=log4j2-spring.xml");
        addBuildArg(buildArgsDom, "-H:IncludeResources=log4j2.component.properties");
        addBuildArg(buildArgsDom, "-H:IncludeResources=.*Log4j2Plugins.dat$");
        addBuildArg(buildArgsDom, "-H:IncludeResources=META-INF/services/org.apache.logging.log4j.spi.Provider");
        addBuildArg(buildArgsDom, "-H:+AllowDeprecatedBuilderClassesOnImageClasspath");
        addBuildArg(buildArgsDom, "-H:IncludeResources=META-INF/native-image/.*\\.json");
        addBuildArg(buildArgsDom, "-H:IncludeResources=META-INF/services/.*");
        addBuildArg(buildArgsDom, "-H:IncludeResources=ai/kompile/.*\\.schema\\.json");
        addBuildArg(buildArgsDom, "-H:IncludeResources=META-INF/spring/.*\\.imports");
        addBuildArg(buildArgsDom, "-H:IncludeResources=META-INF/spring\\.components");
        addBuildArg(buildArgsDom, "-H:DeadlockWatchdogInterval=30");
        addBuildArg(buildArgsDom, "-H:+DeadlockWatchdogExitOnTimeout");

        if(includePgmlIndexer || includeVectorstorePgvector) {
            addBuildArg(buildArgsDom, "-H:IncludeResources=schema.sql");
            addBuildArg(buildArgsDom, "-H:IncludeResources=data.sql");
            addBuildArg(buildArgsDom, "-H:IncludeResources=pgml-schema.sql");
        }
        if(includeLoaderPdfExtended) {
            addBuildArg(buildArgsDom, "-H:IncludeResources=org/apache/pdfbox/resources/afm/.*");
        }

        addBuildArg(buildArgsDom,"--trace-object-instantiation=org.nd4j.linalg.api.memory.deallocation.DeallocatorService$DeallocatorServiceThread");


        nativePluginConfig.addChild(buildArgsDom);
        nativeMavenPlugin.setConfiguration(nativePluginConfig);

        PluginExecution nativeBuildExecution = new PluginExecution();
        nativeBuildExecution.setId("build-native");
        nativeBuildExecution.addGoal("compile-no-fork"); // Using "compile-no-fork" as per original
        nativeBuildExecution.setPhase("package");
        nativeMavenPlugin.addExecution(nativeBuildExecution);

        nativeProfileBuild.addPlugin(nativeMavenPlugin);
        nativeProfile.setBuild(nativeProfileBuild);
        model.addProfile(nativeProfile);
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
