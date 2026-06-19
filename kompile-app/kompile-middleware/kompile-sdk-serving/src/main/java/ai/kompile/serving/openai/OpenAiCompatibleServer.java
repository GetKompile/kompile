package ai.kompile.serving.openai;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import org.eclipse.deeplearning4j.llm.config.TokenizerConfig;
import org.eclipse.deeplearning4j.llm.generation.GenerationPipeline;
import org.eclipse.deeplearning4j.llm.generation.GenerationPipelineConfig;
import org.eclipse.deeplearning4j.llm.generation.sampling.SamplingConfig;
import org.eclipse.deeplearning4j.llm.tokenizer.ChatTemplate;
import org.eclipse.deeplearning4j.llm.tokenizer.HuggingFaceTokenizer;
import org.eclipse.deeplearning4j.pipeline.AutoModel;
import org.eclipse.deeplearning4j.pipeline.GenerationConfig;
import org.nd4j.autodiff.samediff.SameDiff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 * Standalone OpenAI-compatible server entry point.
 * Can be run directly via java -jar or launched from the CLI.
 */
@CommandLine.Command(name = "kompile-sdk-serve",
        description = "Launch an OpenAI-compatible API server for local LLM inference",
        mixinStandardHelpOptions = true)
public class OpenAiCompatibleServer implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleServer.class);

    @CommandLine.Option(names = {"--model-path"}, required = true,
            description = "Path to HuggingFace-format model directory")
    private String modelPath;

    @CommandLine.Option(names = {"--port"}, defaultValue = "8080",
            description = "Server port (default: ${DEFAULT-VALUE})")
    private int port;

    @CommandLine.Option(names = {"--host"}, defaultValue = "0.0.0.0",
            description = "Server bind host (default: ${DEFAULT-VALUE})")
    private String host;

    @CommandLine.Option(names = {"--temperature"}, defaultValue = "0.7",
            description = "Default temperature (default: ${DEFAULT-VALUE})")
    private double temperature;

    @CommandLine.Option(names = {"--max-tokens"}, defaultValue = "256",
            description = "Default max tokens (default: ${DEFAULT-VALUE})")
    private int maxTokens;

    @CommandLine.Option(names = {"--chat-template"},
            description = "Chat template type: auto, chatml, llama2, vicuna, alpaca (default: auto)")
    private String chatTemplateType = "auto";

    @CommandLine.Option(names = {"--model-id"},
            description = "Model identifier returned in API responses (default: directory name)")
    private String modelId;

    @Override
    public Integer call() throws Exception {
        File modelDir = new File(modelPath);
        if (!modelDir.exists() || !modelDir.isDirectory()) {
            log.error("Model path does not exist or is not a directory: {}", modelPath);
            return 1;
        }

        if (modelId == null) {
            modelId = modelDir.getName();
        }

        log.info("Loading model from: {}", modelPath);

        // Load model
        SameDiff model = AutoModel.fromPretrained(modelDir);
        log.info("Model loaded successfully");

        // Load tokenizer
        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.fromDirectory(modelDir);
        log.info("Tokenizer loaded successfully");

        // Load generation config if available
        SamplingConfig.SamplingConfigBuilder samplingBuilder = SamplingConfig.builder()
                .temperature(temperature)
                .maxNewTokens(maxTokens)
                .doSample(temperature > 0)
                .topP(0.9)
                .topK(50)
                .eosTokenId(tokenizer.getEosTokenId())
                .padTokenId(tokenizer.getPadTokenId());

        Optional<GenerationConfig> genConfig = GenerationConfig.loadIfExists(modelDir);
        if (genConfig.isPresent()) {
            GenerationConfig gc = genConfig.get();
            log.info("Loaded generation_config.json");
            if (gc.getTemperature() != null && chatTemplateType.equals("auto")) {
                samplingBuilder.temperature(gc.getEffectiveTemperature());
            }
            if (gc.getTopP() != null) {
                samplingBuilder.topP(gc.getEffectiveTopP());
            }
            if (gc.getTopK() != null) {
                samplingBuilder.topK(gc.getEffectiveTopK());
            }
            if (gc.getMaxNewTokens() != null) {
                samplingBuilder.maxNewTokens(gc.getMaxNewTokens());
            }
            if (gc.getEosTokenId() != null) {
                samplingBuilder.eosTokenId(gc.getEosTokenId());
            }
        }

        SamplingConfig defaultConfig = samplingBuilder.build();

        // Build GenerationPipeline
        GenerationPipelineConfig pipelineConfig = GenerationPipelineConfig.builder()
                .decoder(model)
                .tokenizer(tokenizer)
                .samplingConfig(defaultConfig)
                .build();
        GenerationPipeline pipeline = GenerationPipeline.create(pipelineConfig);
        log.info("GenerationPipeline initialized");

        // Load chat template
        ChatTemplate chatTemplate = loadChatTemplate(modelDir);
        if (chatTemplate != null) {
            log.info("Chat template loaded");
        } else {
            log.warn("No chat template found, using fallback formatting");
        }

        // Create bridge and routes
        ModelBridge bridge = new ModelBridge(pipeline, chatTemplate, modelId);
        OpenAiRoutes routes = new OpenAiRoutes(bridge, defaultConfig);

        // Start server
        Vertx vertx = Vertx.vertx();
        Router router = Router.router(vertx);
        routes.register(router);

        CountDownLatch latch = new CountDownLatch(1);
        HttpServer server = vertx.createHttpServer();
        server.requestHandler(router)
                .listen(port, host, ar -> {
                    if (ar.succeeded()) {
                        log.info("OpenAI-compatible server listening on {}:{}", host, port);
                        log.info("Model: {}", modelId);
                        log.info("Endpoints:");
                        log.info("  GET  http://{}:{}/health", host, port);
                        log.info("  GET  http://{}:{}/v1/models", host, port);
                        log.info("  POST http://{}:{}/v1/chat/completions", host, port);
                    } else {
                        log.error("Failed to start server", ar.cause());
                        latch.countDown();
                    }
                });

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down server...");
            server.close();
            vertx.close();
            tokenizer.close();
            latch.countDown();
        }));

        latch.await();
        return 0;
    }

    private ChatTemplate loadChatTemplate(File modelDir) {
        if (chatTemplateType != null && !chatTemplateType.equals("auto")) {
            switch (chatTemplateType.toLowerCase()) {
                case "chatml":
                    return ChatTemplate.chatML();
                case "llama2":
                case "llama":
                    return ChatTemplate.llama2();
                case "vicuna":
                    return ChatTemplate.vicuna();
                case "alpaca":
                    return ChatTemplate.alpaca();
                default:
                    log.warn("Unknown chat template type: {}, falling back to auto-detection", chatTemplateType);
            }
        }

        // Auto-detect from tokenizer_config.json
        try {
            File tokenizerConfigFile = new File(modelDir, "tokenizer_config.json");
            if (tokenizerConfigFile.exists()) {
                TokenizerConfig config = TokenizerConfig.fromFile(tokenizerConfigFile);
                if (config.hasChatTemplate()) {
                    return ChatTemplate.fromConfig(config);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load chat template from tokenizer_config.json: {}", e.getMessage());
        }

        return null;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new OpenAiCompatibleServer()).execute(args);
        System.exit(exitCode);
    }
}
