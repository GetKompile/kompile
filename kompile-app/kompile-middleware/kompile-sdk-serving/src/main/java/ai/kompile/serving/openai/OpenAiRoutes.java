package ai.kompile.serving.openai;

import ai.kompile.serving.openai.dto.*;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import org.eclipse.deeplearning4j.llm.generation.sampling.SamplingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

/**
 * Registers OpenAI-compatible HTTP routes on a Vert.x Router.
 */
public class OpenAiRoutes {

    private static final Logger log = LoggerFactory.getLogger(OpenAiRoutes.class);

    private final ModelBridge modelBridge;
    private final SamplingConfig defaultConfig;
    private final ObjectMapper objectMapper;

    public OpenAiRoutes(ModelBridge modelBridge, SamplingConfig defaultConfig) {
        this.modelBridge = modelBridge;
        this.defaultConfig = defaultConfig;
        this.objectMapper = JsonUtils.standardMapper();
    }

    /**
     * Registers all routes on the given router.
     */
    public void register(Router router) {
        router.route().handler(BodyHandler.create());

        router.get("/health").handler(this::handleHealth);
        router.get("/v1/models").handler(this::handleListModels);
        router.post("/v1/chat/completions").handler(this::handleChatCompletions);
    }

    private void handleHealth(RoutingContext ctx) {
        ctx.response()
                .putHeader("Content-Type", "application/json")
                .end("{\"status\":\"ok\"}");
    }

    private void handleListModels(RoutingContext ctx) {
        try {
            ModelListResponse response = ModelListResponse.builder()
                    .object("list")
                    .data(Collections.singletonList(
                            ModelListResponse.ModelObject.builder()
                                    .id(modelBridge.getModelId())
                                    .object("model")
                                    .created(System.currentTimeMillis() / 1000)
                                    .ownedBy("local")
                                    .build()))
                    .build();

            ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            sendError(ctx, 500, "Failed to list models: " + e.getMessage());
        }
    }

    private void handleChatCompletions(RoutingContext ctx) {
        try {
            String body = ctx.body().asString();
            ChatCompletionRequest request = objectMapper.readValue(body, ChatCompletionRequest.class);

            if (request.getMessages() == null || request.getMessages().isEmpty()) {
                sendError(ctx, 400, "messages field is required and must not be empty");
                return;
            }

            boolean stream = request.getStream() != null && request.getStream();

            if (stream) {
                handleStreamingCompletion(ctx, request);
            } else {
                handleNonStreamingCompletion(ctx, request);
            }
        } catch (Exception e) {
            log.error("Error handling chat completion request", e);
            sendError(ctx, 500, "Internal server error: " + e.getMessage());
        }
    }

    private void handleNonStreamingCompletion(RoutingContext ctx, ChatCompletionRequest request) {
        // Run generation on a worker thread to avoid blocking the event loop
        ctx.vertx().executeBlocking(promise -> {
            try {
                ChatCompletionResponse response = modelBridge.generate(request, defaultConfig);
                promise.complete(response);
            } catch (Exception e) {
                promise.fail(e);
            }
        }, false, ar -> {
            if (ar.succeeded()) {
                try {
                    ChatCompletionResponse response = (ChatCompletionResponse) ar.result();
                    ctx.response()
                            .putHeader("Content-Type", "application/json")
                            .end(objectMapper.writeValueAsString(response));
                } catch (Exception e) {
                    sendError(ctx, 500, "Failed to serialize response: " + e.getMessage());
                }
            } else {
                log.error("Generation failed", ar.cause());
                sendError(ctx, 500, "Generation failed: " + ar.cause().getMessage());
            }
        });
    }

    private void handleStreamingCompletion(RoutingContext ctx, ChatCompletionRequest request) {
        HttpServerResponse response = ctx.response();
        response.putHeader("Content-Type", "text/event-stream");
        response.putHeader("Cache-Control", "no-cache");
        response.putHeader("Connection", "keep-alive");
        response.setChunked(true);

        // Run generation on a worker thread
        ctx.vertx().executeBlocking(promise -> {
            try {
                modelBridge.generateStreaming(request, defaultConfig,
                        chunk -> {
                            try {
                                String json = objectMapper.writeValueAsString(chunk);
                                response.write("data: " + json + "\n\n");
                            } catch (Exception e) {
                                log.error("Failed to serialize chunk", e);
                            }
                        },
                        () -> {
                            response.write("data: [DONE]\n\n");
                            response.end();
                        });
                promise.complete();
            } catch (Exception e) {
                promise.fail(e);
            }
        }, false, ar -> {
            if (ar.failed()) {
                log.error("Streaming generation failed", ar.cause());
                if (!response.ended()) {
                    response.end();
                }
            }
        });
    }

    private void sendError(RoutingContext ctx, int statusCode, String message) {
        try {
            ErrorResponse error = ErrorResponse.of(message, "invalid_request_error",
                    statusCode == 400 ? "invalid_request" : "server_error");
            ctx.response()
                    .setStatusCode(statusCode)
                    .putHeader("Content-Type", "application/json")
                    .end(objectMapper.writeValueAsString(error));
        } catch (Exception e) {
            ctx.response()
                    .setStatusCode(500)
                    .end("{\"error\":{\"message\":\"Internal error\"}}");
        }
    }
}
