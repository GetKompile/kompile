/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.channel.api.ChannelTestRequest;
import ai.kompile.cli.main.auth.channel.ChannelControlPlaneClient;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Non-secret harness access to channel discovery, login, status, and allowlisted delivery. */
public final class ChannelTool implements CliTool {

    private final String baseUrl;
    private final ObjectMapper mapper;

    public ChannelTool() {
        this(null, JsonUtils.standardMapper());
    }

    public ChannelTool(String baseUrl, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.mapper = mapper == null ? JsonUtils.standardMapper() : mapper;
    }

    @Override
    public String id() {
        return "channel";
    }

    @Override
    public String description() {
        return "Manage non-secret conversational channel login and usage from the harness. "
                + "Actions: providers, auth, login, connections, status, send. Login never returns "
                + "tokens; send is restricted to targets explicitly allowlisted on a connection "
                + "whose operator enabled harness delivery.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode actions = properties.putObject("action").put("type", "string").putArray("enum");
        for (String action : new String[]{"providers", "auth", "login", "connections", "status", "send"}) {
            actions.add(action);
        }
        properties.putObject("provider").put("type", "string")
                .put("description", "Channel provider id for auth/login.");
        properties.putObject("name").put("type", "string")
                .put("description", "Named channel connection for status/send.");
        properties.putObject("target").put("type", "string")
                .put("description", "Preconfigured allowlisted target for send.");
        properties.putObject("message").put("type", "string")
                .put("description", "Message to deliver through the named connection.");
        properties.putObject("confirmed").put("type", "boolean")
                .put("description", "Required true for login or external delivery.");
        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() {
        return "channel";
    }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        return McpToolAnnotations.NETWORK;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String action = params.path("action").asText("").trim().toLowerCase();
        try {
            ChannelControlPlaneClient client = new ChannelControlPlaneClient(baseUrl);
            return switch (action) {
                case "providers" -> json("Channel providers", client.providers());
                case "auth" -> json("Channel auth", client.providerAuth(required(params, "provider")));
                case "login" -> login(client, params, context);
                case "connections" -> json("Channel connections", client.connections());
                case "status" -> json("Channel status", client.connection(required(params, "name")));
                case "send" -> send(client, params, context);
                default -> throw new ToolExecutionException(
                        "Unknown channel action: " + action
                                + ". Use providers, auth, login, connections, status, or send.");
            };
        } catch (ToolExecutionException error) {
            throw error;
        } catch (Exception error) {
            throw new ToolExecutionException(
                    "Channel " + (action.isEmpty() ? "operation" : action) + " failed: "
                            + safeMessage(error), error);
        }
    }

    private ToolResult login(
            ChannelControlPlaneClient client,
            JsonNode params,
            ToolContext context) throws Exception {
        requireConfirmation(params, "channel login");
        context.checkPermission("channel.login", "Start provider channel login");
        String provider = required(params, "provider");
        var auth = client.providerAuth(provider);
        if (!auth.loginSupported()) {
            return ToolResult.success("Channel login", auth.guidance());
        }
        var login = client.browserLogin();
        String instructions = "Open Agent Hub → Channels, enter this one-time web login code "
                + "(expires " + login.expiresAt() + "), complete the " + provider
                + " login there, then call channel action=auth again:\n" + login.code();
        if (context.getOutputConsumer() != null) {
            context.emitOutput(instructions);
            return ToolResult.success("Channel login", "user_action_required for " + provider);
        }
        return ToolResult.success("Channel login", instructions);
    }

    private ToolResult send(
            ChannelControlPlaneClient client,
            JsonNode params,
            ToolContext context) throws Exception {
        requireConfirmation(params, "external channel delivery");
        context.checkPermission("channel.send", "Send through a preconfigured channel target");
        return json("Channel delivery", client.deliver(
                required(params, "name"),
                new ChannelTestRequest(required(params, "target"), required(params, "message"))));
    }

    private ToolResult json(String title, Object value) throws Exception {
        return ToolResult.success(title, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    private static String required(JsonNode params, String name) throws ToolExecutionException {
        String value = params.path(name).asText("").trim();
        if (value.isEmpty()) {
            throw new ToolExecutionException("channel action requires '" + name + "'");
        }
        return value;
    }

    private static void requireConfirmation(JsonNode params, String operation)
            throws ToolExecutionException {
        if (!params.path("confirmed").asBoolean(false)) {
            throw new ToolExecutionException(operation + " requires confirmed=true");
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.length() <= 300 ? message : message.substring(0, 300);
    }
}
