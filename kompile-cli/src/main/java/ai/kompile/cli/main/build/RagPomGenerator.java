/*
 * Copyright 2025 Kompile Inc.
 * *
 * * Licensed under the Apache License, Version 2.0 (the "License");
 * * you may not use this file except in compliance with the License.
 * * You may obtain a copy of the License at
 * *
 * * http://www.apache.org/licenses/LICENSE-2.0
 * *
 * * Unless required by applicable law or agreed to in writing, software
 * * distributed under the License is distributed on an "AS IS" BASIS,
 * * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * * See the License for the specific language governing permissions and
 * * limitations under the License.
 */

package ai.kompile.cli.main.build;

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

    // RESTORED as the single language flag
    @CommandLine.Option(names = {"--supportedLanguages"},
            description = "Comma-separated list of ISO 639-1 language codes (e.g., en,de,es). " +
                    "Used to determine which OpenNLP sentence models to download if --includeChunkerSentence is active. "+
                    "The first language in this list will also be set as the default 'kompile.opennlp.sentence.language' property.",
            defaultValue = "en", split = ",")
    private List<String> supportedLanguages = new ArrayList<>(Collections.singletonList("en"));

    @CommandLine.Option(names = {"--buildNative"}, description = "Configure build for GraalVM native image", defaultValue = "true")
    private boolean buildNative = true;

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

    private void generateMainApplicationClass(File projectBaseDir, String packageName, String className) throws IOException {
        String packagePath = packageName.replace('.', '/');
        Path mainJavaDir = Paths.get(projectBaseDir.getAbsolutePath(), "src", "main", "java", packagePath);
        Files.createDirectories(mainJavaDir);

        Path mainAppFile = mainJavaDir.resolve(className + ".java");
        String mainAppContent = String.format(
                "package %s;\n\n" +
                        "import ai.kompile.app.MainApplication;\n" +
                        "import org.springframework.boot.SpringApplication;\n" +
                        "import org.springframework.boot.autoconfigure.SpringBootApplication;\n" +
                        "import org.springframework.context.annotation.Import;\n\n" +
                        "@SpringBootApplication\n" +
                        "@Import(MainApplication.class)\n" +
                        "public class %s {\n\n" +
                        "    public static void main(String[] args) {\n" +
                        "        SpringApplication.run(%s.class, args);\n" +
                        "    }\n" +
                        "}\n",
                packageName, className, className
        );

        try (FileWriter writer = new FileWriter(mainAppFile.toFile())) {
            writer.write(mainAppContent);
        }
        System.out.println("Generated main application class: " + mainAppFile.toAbsolutePath());
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

        List<String> downloadedModelNamesForNative = new ArrayList<>();
        if (includeChunkerSentence) {
            downloadedModelNamesForNative.addAll(downloadOpenNLPModelsForSupportedLanguages(projectDir, this.supportedLanguages));
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

        String defaultRuntimeLangForOpenNLP = "en";
        if (this.supportedLanguages != null && !this.supportedLanguages.isEmpty()) {
            String firstSpecifiedLang = this.supportedLanguages.get(0).toLowerCase().trim();
            if (LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME.containsKey(firstSpecifiedLang)) {
                defaultRuntimeLangForOpenNLP = firstSpecifiedLang;
            } else {
                System.out.println("Warning: First language '" + this.supportedLanguages.get(0) +
                        "' from --supportedLanguages is not in the known model list for setting runtime default property 'kompile.opennlp.sentence.language'. " +
                        "Defaulting property to 'en'.");
            }
        }
        props.setProperty("kompile.opennlp.sentence.language", defaultRuntimeLangForOpenNLP);

        model.setProperties(props);

        addApplicationDependencies();
        addApplicationBuild();

        if (buildNative) {
            addNativeProfile(CORE_APP_MAIN_CLASS_FQCN, downloadedModelNamesForNative);
        }

        addSpringRepositories();

        MavenXpp3Writer mavenXpp3Writer = new MavenXpp3Writer();
        File finalPomFile = outputFile.isDirectory() ? new File(outputFile, "pom-rag-instance.xml") : outputFile;

        try (FileWriter fileWriter = new FileWriter(finalPomFile)) {
            mavenXpp3Writer.write(fileWriter, model);
            System.out.println("Successfully generated RAG application POM: " + finalPomFile.getAbsolutePath());
        }
        return null;
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

        if (includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer) {
            addDependency(defaultDependencies, "org.postgresql", "postgresql", "${postgres.version}", "compile", null, false);
        }

        addDependency(defaultDependencies, "org.projectlombok", "lombok", "${lombok.version}", "provided", null, true);
        addDependency(defaultDependencies, "com.fasterxml.jackson.core", "jackson-databind", "${jackson.version}");
        addDependency(defaultDependencies, "com.google.guava", "guava", "${guava.version}");
        addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter-test", "${spring-boot.version}", "test", null, false);
        model.setDependencies(defaultDependencies);
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
                            "  <release>${java.version}</release>" +
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

    private void addNativeProfile(String nativeImageMainClassFqcn, List<String> downloadedOpenNLPModelLocalFilenames) {
        Profile nativeProfile = new Profile();
        nativeProfile.setId("native");
        Build nativeProfileBuild = new Build();

        Plugin springBootPluginNative = createPlugin("org.springframework.boot", "spring-boot-maven-plugin", "${spring-boot.version}");
        try {
            Xpp3Dom springBootNativeConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "  <mainClass>" + CORE_APP_MAIN_CLASS_FQCN + "</mainClass>" +
                            "  <classifier>exec</classifier>" +
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
        repackageExecutionNative.setId("repackage-native-profile");
        repackageExecutionNative.addGoal("repackage");
        springBootPluginNative.addExecution(repackageExecutionNative);
        nativeProfileBuild.addPlugin(springBootPluginNative);

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

        Plugin nativeMavenPlugin = createPlugin("org.graalvm.buildtools", "native-maven-plugin", "${native-maven-plugin.version}");
        nativeMavenPlugin.setExtensions(true);

        Xpp3Dom nativePluginConfig = new Xpp3Dom("configuration");
        addChild(nativePluginConfig, "imageName", "${native.image.name}");
        addChild(nativePluginConfig, "mainClass", nativeImageMainClassFqcn);
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
        addBuildArg(buildArgsDom, "-H:-AddAllFileSystemProviders");
        addBuildArg(buildArgsDom, "--enable-url-protocols=http,https");
        addBuildArg(buildArgsDom, "-Djava.awt.headless=true");
        String initializeAtBuildTimeArg = "org.apache.logging.log4j.Util,org.apache.logging.log4j.status.StatusLogger,org.apache.logging.log4j.util.ProviderUtil,org.apache.logging.log4j.util.PropertySource$Util,org.apache.logging.log4j.core.impl.Log4jProvider,org.apache.logging.log4j.spi.AbstractLogger,org.apache.logging.log4j.core.impl.Log4jContextFactory,org.apache.logging.log4j.core.selector.ClassLoaderContextSelector,org.apache.logging.log4j.core.LifeCycle$State,org.apache.logging.log4j.status.StatusLogger,org.apache.logging.log4j.spi.StandardLevel,,org.apache.logging.log4j.util.Strings,org.apache.logging.log4j.Level,org.apache.logging.log4j.util.PropertiesUtil,org.apache.logging.log4j.util.OsgiServiceLocator,org.apache.logging.log4j.util.PropertyFilePropertySource,org.apache.logging.log4j.message.ParameterFormatter,org.apache.logging.log4j.status.StatusLogger$Config,org.apache.logging.log4j.status.StatusLogger$InstanceHolder";
        addBuildArg(buildArgsDom, "--initialize-at-build-time=" + initializeAtBuildTimeArg);
        String initializeAtRunTimeArg = "org.apache.poi.util.RandomSingleton,sun.awt.X11.XWindow,sun.awt.X11.XDataTransferer,sun.rmi.server,java.rmi.server,sun.java.rmi.server,sun.rmi.transport,org.apache.tomcat.jni.SSL,sun.awt.X11GraphicsConfig,reactor.core.scheduler.BoundedElasticScheduler,reactor.core.scheduler.Schedulers,org.springframework.web.reactive.function.client.DefaultExchangeStrategiesBuilder,org.springframework.boot.loader.ref.DefaultCleaner,org.apache.tomcat.util.net.openssl.OpenSSLContext,org.apache.tomcat.util.net.openssl.OpenSSLEngine,sun.awt.dnd.SunDropTargetContextPeer$EventDispatcher,org.springframework.core.io.VfsUtils,org.springframework.boot.loader.ref.Cleaner,org.springframework.boot.loader.ref.DefaultCleaner,reactor.core.scheduler.BoundedElasticScheduler,reactor.core.scheduler.Schedulers,reactor.core.scheduler.BoundedElasticScheduler,org.springframework.web.reactive.function.client.DefaultExchangeStrategiesBuilder,reactor.core.scheduler.SchedulerState$DisposeAwaiterRunnable,org.apache.catalina.mbeans.MBeanUtils,org.apache.catalina.mbeans.MBeanFactory";
        addBuildArg(buildArgsDom, "--initialize-at-run-time=" + initializeAtRunTimeArg);
        addBuildArg(buildArgsDom, "-H:IncludeResources=log4j2.xml");
        addBuildArg(buildArgsDom, "--trace-class-initialization=org.apache.poi.util.RandomSingleton,sun.awt.X11.XDataTransferer,sun.awt.X11.XWindow,sun.rmi.server.Util,java.security.SecureRandom,com.sun.jndi.dns.DnsClient");
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
        addBuildArg(buildArgsDom, "--trace-class-initialization=sun.rmi.server.UnicastRef,java.rmi.server.LogStream,com.sun.jndi.dns.DnsClient");

        if(includeLoaderPdfExtended) {
            addBuildArg(buildArgsDom, "-H:IncludeResources=org/apache/pdfbox/resources/afm/.*");
        }

        if (includeChunkerSentence && downloadedOpenNLPModelLocalFilenames != null) {
            for (String modelLocalFileName : downloadedOpenNLPModelLocalFilenames) {
                if (modelLocalFileName != null && !modelLocalFileName.isEmpty()) {
                    addBuildArg(buildArgsDom, "-H:IncludeResources=" + OPENNLP_MODEL_TARGET_DIR_IN_RESOURCES + "/" + modelLocalFileName);
                }
            }
        }

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