package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Named, credential-free launch selections. Profiles never fall back to another project or global scope. */
public final class ChatProfiles {
    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper();

    private ChatProfiles() {}

    public static Path path(Path projectRoot) {
        return ChatConfig.projectConfigPath(projectRoot).resolveSibling("chat-profiles.json");
    }

    /** An explicit allowlist, not a serialized ChatConfig (which also owns transient authentication state). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Profile(String name, String vendor, String mode, String provider,
                          String model, String thinking, String baseUrl, String authenticationMethod,
                          boolean fastMode, String agent) {
        public Profile {
            name = required(name, "Profile name");
            vendor = required(vendor, "Vendor").toLowerCase(Locale.ROOT);
            if (!"standard".equals(mode) && !"passthrough-managed".equals(mode)
                    && !"passthrough-direct".equals(mode) && !"judge".equals(mode)) {
                throw new IllegalArgumentException("Unsupported profile mode: " + mode);
            }
            if ("standard".equals(mode) || "judge".equals(mode)) provider = required(provider, "Provider");
            else agent = required(agent, "Agent");
            if ("judge".equals(mode)) {
                provider = JudgeDefaults.capabilityProvider(provider);
                model = required(model, "Judge model");
                thinking = thinking == null || thinking.isBlank() ? null : required(thinking, "Thinking");
                if (!vendor.equals(JudgeDefaults.vendor(provider))) {
                    throw new IllegalArgumentException("Judge profile vendor must match its provider");
                }
                if (baseUrl != null || authenticationMethod != null || fastMode || agent != null) {
                    throw new IllegalArgumentException("Judge profiles store model/thinking only, not chat routing");
                }
            }
        }

        public ChatConfig toConfig() {
            if ("judge".equals(mode)) throw new IllegalStateException("Judge profiles are not chat launch profiles");
            ChatConfig config = new ChatConfig(provider, null, model, baseUrl);
            config.setThinking(thinking);
            config.setAuthenticationMethod(authenticationMethod);
            config.setFastMode(fastMode);
            config.setChatMode("standard".equals(mode) ? "standard" : "passthrough");
            config.setPassthroughManaged(!"passthrough-direct".equals(mode));
            if (agent != null) config.setPassthroughAgent(agent);
            return config;
        }
    }

    public static Profile capture(String name, ChatConfig config) {
        boolean passthrough = "passthrough".equals(config.getChatMode());
        if (!passthrough && !"standard".equals(config.getChatMode())) {
            throw new IllegalArgumentException("Resume actions are not launch profiles");
        }
        String vendor = passthrough ? switch (required(config.getPassthroughAgent(), "Agent")
                .toLowerCase(Locale.ROOT)) {
            case "claude" -> "anthropic";
            case "codex" -> "openai";
            default -> config.getPassthroughAgent();
        } : SetupWizard.vendorForProvider(config.getProvider());
        return new Profile(name, vendor, mode(config), passthrough ? null : config.getProvider(),
                config.getModel(), config.getThinking(), passthrough ? null : config.getBaseUrl(),
                passthrough ? null : config.getAuthenticationMethod(),
                !passthrough && config.isFastMode(), passthrough ? config.getPassthroughAgent() : null);
    }

    /** Model/thinking selections share the profile store, never the main chat's credentials or routing. */
    public static Profile captureJudge(String name, String provider, String model, String thinking) {
        return new Profile(name, JudgeDefaults.vendor(provider), "judge", provider,
                model, thinking, null, null, false, null);
    }

    public static String mode(ChatConfig config) {
        return "passthrough".equals(config.getChatMode())
                ? (config.isPassthroughManaged() ? "passthrough-managed" : "passthrough-direct")
                : config.getChatMode();
    }

    public static List<Profile> list(Path projectRoot, String chatMode) throws IOException {
        ObjectNode root = read(projectRoot);
        List<Profile> result = new ArrayList<>();
        for (JsonNode vendor : root.path("vendors")) {
            for (JsonNode mode : vendor) {
                for (JsonNode value : mode) {
                    Profile profile = MAPPER.treeToValue(value, Profile.class);
                    if (profile.mode().equals(chatMode)
                            || ("passthrough".equals(chatMode) && profile.mode().startsWith("passthrough-"))) {
                        result.add(profile);
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    /** Returns false rather than silently replacing a same-name profile. */
    public static synchronized boolean save(Path projectRoot, Profile profile, boolean replace) throws IOException {
        ObjectNode root = read(projectRoot);
        ObjectNode names = object(object(object(root, "vendors"), profile.vendor()), profile.mode());
        if (names.has(profile.name()) && !replace) return false;
        names.set(profile.name(), MAPPER.valueToTree(profile));
        write(projectRoot, root);
        return true;
    }

    public static Profile activeJudge(Path projectRoot, String provider) throws IOException {
        ObjectNode root = read(projectRoot);
        String vendor = JudgeDefaults.vendor(provider);
        JsonNode name = root.path("activeJudges").get(vendor);
        return name == null ? null : MAPPER.treeToValue(
                root.path("vendors").path(vendor).path("judge").get(name.asText()), Profile.class);
    }

    /** Select an existing project judge profile; null clears the selection back to vendor defaults. */
    public static synchronized void activateJudge(Path projectRoot, String provider, String name) throws IOException {
        ObjectNode root = read(projectRoot);
        String vendor = required(JudgeDefaults.vendor(provider), "Vendor");
        if (name == null) {
            if (!root.path("activeJudges").has(vendor)) return;
            ((ObjectNode) root.get("activeJudges")).remove(vendor);
        } else {
            name = required(name, "Profile name");
            if (!root.path("vendors").path(vendor).path("judge").has(name)) {
                throw new IllegalArgumentException("No judge profile for " + vendor + " / " + name);
            }
            object(root, "activeJudges").put(vendor, name);
        }
        write(projectRoot, root);
    }

    /** Deleting an active profile also clears its selection, in the same atomic write. */
    public static synchronized boolean deleteJudge(Path projectRoot, String provider, String name) throws IOException {
        ObjectNode root = read(projectRoot);
        String vendor = required(JudgeDefaults.vendor(provider), "Vendor");
        name = required(name, "Profile name");
        JsonNode profiles = root.path("vendors").path(vendor).path("judge");
        if (!profiles.has(name)) return false;
        ((ObjectNode) profiles).remove(name);
        if (name.equals(root.path("activeJudges").path(vendor).asText())) {
            ((ObjectNode) root.get("activeJudges")).remove(vendor);
        }
        write(projectRoot, root);
        return true;
    }

    private static void write(Path projectRoot, ObjectNode root) throws IOException {
        Path destination = path(projectRoot);
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "chat-profiles-", ".tmp");
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static ObjectNode read(Path projectRoot) throws IOException {
        Path file = path(projectRoot);
        if (!Files.exists(file)) return MAPPER.createObjectNode();
        JsonNode root = MAPPER.readTree(file.toFile());
        if (!(root instanceof ObjectNode object)) throw new IOException("Invalid chat profiles: " + file);
        // Refuse malformed stores rather than overwriting unreadable profiles on the next save.
        if (object.has("vendors")) {
            if (!object.get("vendors").isObject()) throw new IOException("Profile vendors must be an object");
            var vendors = object.get("vendors").fields();
            while (vendors.hasNext()) {
                var vendor = vendors.next();
                if (!vendor.getValue().isObject()) throw new IOException("Profile vendor must be an object");
                var modes = vendor.getValue().fields();
                while (modes.hasNext()) {
                    var mode = modes.next();
                    if (!mode.getValue().isObject()) throw new IOException("Profile mode must be an object");
                    var profiles = mode.getValue().fields();
                    while (profiles.hasNext()) {
                        var entry = profiles.next();
                        if (!entry.getValue().isObject()) throw new IOException("Profile must be an object");
                        Profile profile = MAPPER.treeToValue(entry.getValue(), Profile.class);
                        if (!profile.vendor().equals(vendor.getKey()) || !profile.mode().equals(mode.getKey())
                                || !profile.name().equals(entry.getKey())) {
                            throw new IOException("Profile does not match its vendor/mode/name slot");
                        }
                    }
                }
            }
        }
        if (object.has("activeJudges")) {
            if (!object.get("activeJudges").isObject()) throw new IOException("Active judges must be an object");
            var active = object.get("activeJudges").fields();
            while (active.hasNext()) {
                var entry = active.next();
                if (!entry.getValue().isTextual() || !object.path("vendors").path(entry.getKey())
                        .path("judge").has(entry.getValue().asText())) {
                    throw new IOException("Active judge must name an existing profile for " + entry.getKey());
                }
            }
        }
        return object;
    }

    private static ObjectNode object(ObjectNode parent, String key) {
        if (!parent.has(key)) parent.set(key, MAPPER.createObjectNode());
        return (ObjectNode) parent.get(key);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " must be nonblank and contain no control characters");
        }
        return value.trim();
    }
}
