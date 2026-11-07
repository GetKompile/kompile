/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.extensions.process;

import ai.kompile.cli.plugin.api.CliExtensionDescriptor;
import ai.kompile.cli.plugin.api.CliToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict, immutable metadata for a trusted local process package, not an authorization grant. */
public final class ProcessExtensionManifest {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");
    private final String id;
    private final String version;
    private final Set<String> provides;
    private final Set<String> requires;
    private final Map<String, Launch> commands;
    private final Launch mcp;
    private final List<Tool> tools;
    private final int timeoutSeconds;

    private ProcessExtensionManifest(JsonNode node) {
        fields(node, Set.of("schemaVersion", "id", "version", "apiVersion", "provides", "requires",
                "commands", "mcp", "tools", "timeoutSeconds"), "manifest");
        integer(node.get("schemaVersion"), 1, 1, "schemaVersion");
        integer(node.get("apiVersion"), 1, 1, "apiVersion");
        id = identifier(text(node.get("id"), "id"), ID, "id");
        version = identifier(text(node.get("version"), "version"), VERSION, "version");
        provides = names(node.get("provides"), "provides");
        requires = names(node.get("requires"), "requires");
        Map<String, Launch> commandMap = new LinkedHashMap<>();
        JsonNode commandNode = node.get("commands");
        if (commandNode != null) {
            object(commandNode, "commands");
            commandNode.fields().forEachRemaining(entry -> commandMap.put(
                    identifier(entry.getKey(), NAME, "command"), launch(entry.getValue())));
        }
        commands = Collections.unmodifiableMap(commandMap);
        mcp = node.has("mcp") ? launch(node.get("mcp")) : null;
        List<Tool> parsedTools = new ArrayList<>();
        Set<String> toolNames = new LinkedHashSet<>();
        JsonNode toolNode = node.get("tools");
        if (toolNode != null) {
            array(toolNode, "tools");
            for (JsonNode value : toolNode) {
                Tool tool = new Tool(value);
                if (!toolNames.add(tool.name())) throw invalid("Duplicate tool name: " + tool.name());
                parsedTools.add(tool);
            }
        }
        tools = List.copyOf(parsedTools);
        if (commands.isEmpty() && tools.isEmpty()) throw invalid("Manifest must provide commands or tools");
        if (tools.isEmpty() != (mcp == null)) throw invalid("mcp and nonempty tools must be declared together");
        timeoutSeconds = node.has("timeoutSeconds")
                ? integer(node.get("timeoutSeconds"), 1, 600, "timeoutSeconds") : 60;
    }

    public static ProcessExtensionManifest parse(JsonNode node) { return new ProcessExtensionManifest(node); }
    public String id() { return id; }
    public String version() { return version; }
    public int apiVersion() { return 1; }
    public Set<String> provides() { return provides; }
    public Set<String> requires() { return requires; }
    public Map<String, Launch> commands() { return commands; }
    public Optional<Launch> mcp() { return Optional.ofNullable(mcp); }
    public List<Tool> tools() { return tools; }
    public int timeoutSeconds() { return timeoutSeconds; }

    public String toolId(String toolName) {
        return "mcp__extension_" + id + "__" + identifier(toolName, TOOL_NAME, "tool name");
    }

    /** Installed support only; conditional capabilities remain optional, never prerequisites. */
    public CliExtensionDescriptor descriptor() {
        return new CliExtensionDescriptor(id, version, apiVersion(), provides, requires,
                tools.stream().map(tool -> new CliToolDescriptor(toolId(tool.name()), tool.requires(),
                        tool.actionRequirements(), tool.conditionalCapabilities())).toList(), commands.keySet());
    }

    public record Launch(String kind, String path, List<String> args) {
        public Launch {
            if (!"executable".equals(kind) && !"jar".equals(kind)) throw invalid("Unsupported launch kind");
            relativePath(path);
            Objects.requireNonNull(args, "args");
            if (args.size() > 128) throw invalid("Too many launch arguments");
            args.forEach(arg -> argument(arg, "argument"));
            args = List.copyOf(args);
        }

        /** Returns literal argv; does not invoke a shell or execute anything. */
        public List<String> command(Path packageDirectory, String javaExecutable) throws IOException {
            Path target = checkedFile(packageDirectory, path);
            List<String> result = new ArrayList<>();
            if (kind.equals("jar")) {
                if (!path.endsWith(".jar") || !Files.isReadable(target)) {
                    throw new IOException("Jar launch requires a readable .jar file: " + target);
                }
                argument(javaExecutable, "Java executable");
                if (javaExecutable.isBlank()) throw invalid("Java executable must be supplied explicitly");
                result.add(javaExecutable);
                result.add("-jar");
            } else if (!Files.isExecutable(target)) {
                throw new IOException("Launch file is not executable: " + target);
            }
            result.add(target.toString());
            result.addAll(args);
            return List.copyOf(result);
        }
    }

    public static final class Tool {
        private final String name;
        private final String description;
        private final JsonNode inputSchema;
        private final Set<String> requires;
        private final Map<String, Set<String>> actionRequirements;
        private final Map<String, Set<String>> conditionalCapabilities;

        private Tool(JsonNode node) {
            fields(node, Set.of("name", "description", "inputSchema", "requires", "actionRequirements",
                    "conditionalCapabilities"), "tool");
            name = identifier(text(node.get("name"), "tool name"), TOOL_NAME, "tool name");
            description = text(node.get("description"), "description");
            if (description.isBlank()) throw invalid("Tool description must not be blank");
            JsonNode schema = node.get("inputSchema");
            object(schema, "inputSchema");
            jsonTree(schema, 0);
            inputSchema = schema.deepCopy();
            requires = names(node.get("requires"), "tool requires");
            actionRequirements = requirements(node.get("actionRequirements"), "actionRequirements");
            conditionalCapabilities = requirements(node.get("conditionalCapabilities"), "conditionalCapabilities");
        }

        public String name() { return name; }
        public String description() { return description; }
        public JsonNode inputSchema() { return inputSchema.deepCopy(); }
        public Set<String> requires() { return requires; }
        public Map<String, Set<String>> actionRequirements() { return actionRequirements; }
        public Map<String, Set<String>> conditionalCapabilities() { return conditionalCapabilities; }
    }

    static String extensionId(String value) { return identifier(value, ID, "id"); }
    static String extensionVersion(String value) { return identifier(value, VERSION, "version"); }

    static Path checkedFile(Path directory, String relative) throws IOException {
        relativePath(relative);
        Path base = directory.toAbsolutePath().normalize();
        noSymlinks(base);
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a package directory: " + base);
        Path file = base.resolve(relative).normalize();
        if (!file.startsWith(base)) throw new IOException("Launch path escapes package");
        noSymlinks(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular package file: " + file);
        return file;
    }

    static void noSymlinks(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Symlink is not allowed: " + current);
        }
    }

    private static Launch launch(JsonNode node) {
        fields(node, Set.of("kind", "path", "args"), "launch");
        List<String> args = new ArrayList<>();
        if (node.has("args")) {
            array(node.get("args"), "args");
            for (JsonNode arg : node.get("args")) args.add(text(arg, "argument"));
        }
        return new Launch(text(node.get("kind"), "kind"), text(node.get("path"), "path"), args);
    }

    private static Map<String, Set<String>> requirements(JsonNode node, String label) {
        if (node == null) return Map.of();
        object(node, label);
        Map<String, Set<String>> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            identifier(entry.getKey(), NAME, "action");
            if (!entry.getValue().isArray()) throw invalid(label + " values must be arrays");
            result.put(entry.getKey(), names(entry.getValue(), label));
        });
        return Collections.unmodifiableMap(result);
    }

    private static Set<String> names(JsonNode node, String label) {
        if (node == null) return Set.of();
        array(node, label);
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode value : node) {
            String name = identifier(text(value, label), NAME, label);
            if (!result.add(name)) throw invalid("Duplicate " + label + ": " + name);
        }
        return Collections.unmodifiableSet(result);
    }

    private static void relativePath(String path) {
        if (path == null || path.isBlank() || path.length() > 4096 || path.indexOf('\\') >= 0
                || path.indexOf(':') >= 0 || path.indexOf('\0') >= 0 || path.startsWith("/")) {
            throw invalid("Launch path must be a portable relative file path");
        }
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw invalid("Invalid launch path component");
        }
    }

    private static String identifier(String value, Pattern pattern, String label) {
        if (value == null || value.length() > 256 || !pattern.matcher(value).matches()) {
            throw invalid("Invalid " + label + ": " + value);
        }
        return value;
    }

    private static void argument(String value, String label) {
        if (value == null || value.length() > 8192 || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Invalid " + label);
        }
    }

    private static String text(JsonNode node, String label) {
        if (node == null || !node.isTextual()) throw invalid(label + " must be a string");
        String value = node.textValue();
        argument(value, label);
        return value;
    }

    private static int integer(JsonNode node, int min, int max, String label) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()
                || node.intValue() < min || node.intValue() > max) throw invalid("Unsupported " + label);
        return node.intValue();
    }

    private static void jsonTree(JsonNode node, int depth) {
        if (depth > 128 || !(node.isContainerNode() || node.isTextual() || node.isBoolean()
                || node.isNumber() || node.isNull())) throw invalid("inputSchema must contain only JSON values");
        if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) {
            throw invalid("inputSchema cannot contain non-finite numbers");
        }
        for (JsonNode child : node) jsonTree(child, depth + 1);
    }

    private static void fields(JsonNode node, Set<String> allowed, String label) {
        object(node, label);
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw invalid("Unknown " + label + " field: " + name);
        });
    }

    private static void object(JsonNode node, String label) {
        if (node == null || !node.isObject()) throw invalid(label + " must be an object");
    }

    private static void array(JsonNode node, String label) {
        if (!node.isArray()) throw invalid(label + " must be an array");
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
