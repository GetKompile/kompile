/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.integration;

import ai.kompile.channel.api.ChannelProviderDescriptor;
import ai.kompile.channel.api.ChannelProviderDescriptor.Capability;
import ai.kompile.channel.api.ChannelProviderDescriptor.Field;
import ai.kompile.channel.api.ChannelProviderDescriptor.FieldType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Server-authoritative schema and validation catalog for built-in channel providers. */
public final class ChannelProviderCatalog {

    private final Map<String, ChannelProviderDescriptor> providers;

    public ChannelProviderCatalog() {
        Map<String, ChannelProviderDescriptor> descriptors = new LinkedHashMap<>();
        register(descriptors, telegram());
        register(descriptors, slack());
        register(descriptors, discord());
        register(descriptors, whatsapp());
        register(descriptors, email());
        this.providers = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(descriptors));
    }

    public List<ChannelProviderDescriptor> providers() {
        return providers.values().stream().toList();
    }

    public ChannelProviderDescriptor require(String providerId) {
        String normalized = normalizeProviderId(providerId);
        ChannelProviderDescriptor descriptor = providers.get(normalized);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unsupported channel provider: " + providerId);
        }
        return descriptor;
    }

    public String normalizeProviderId(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId is required");
        }
        return providerId.trim().toLowerCase(Locale.ROOT);
    }

    public Map<String, Object> normalizeSettings(String providerId, Map<String, Object> supplied) {
        ChannelProviderDescriptor descriptor = require(providerId);
        Map<String, Object> raw = supplied == null ? Map.of() : supplied;
        Map<String, Field> fields = byName(descriptor.settings());
        rejectUnknown(raw.keySet(), fields.keySet(), "setting");

        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Field field : descriptor.settings()) {
            Object value = raw.containsKey(field.name()) ? raw.get(field.name()) : field.defaultValue();
            if (value != null) {
                value = convert(field, value);
                normalized.put(field.name(), value);
            }
            if (field.required() && missing(value)) {
                throw new IllegalArgumentException("Missing required setting: " + field.name());
            }
        }
        validateSemanticSettings(descriptor.id(), normalized);
        return Map.copyOf(normalized);
    }

    public void validateSecrets(String providerId, Map<String, String> secrets) {
        ChannelProviderDescriptor descriptor = require(providerId);
        Map<String, String> values = secrets == null ? Map.of() : secrets;
        Map<String, Field> fields = byName(descriptor.secrets());
        rejectUnknown(values.keySet(), fields.keySet(), "secret");
        for (Field field : descriptor.secrets()) {
            if (field.required() && (values.get(field.name()) == null || values.get(field.name()).isBlank())) {
                throw new IllegalArgumentException("Missing required secret: " + field.name());
            }
        }
        values.forEach((name, value) -> {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Secret must not be blank: " + name);
            }
        });
    }

    public void validateSecretsForUpdate(String providerId, Map<String, String> secrets) {
        ChannelProviderDescriptor descriptor = require(providerId);
        Map<String, String> values = secrets == null ? Map.of() : secrets;
        Map<String, Field> fields = byName(descriptor.secrets());
        rejectUnknown(values.keySet(), fields.keySet(), "secret");
        values.forEach((name, value) -> {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Secret must not be blank: " + name);
            }
        });
    }

    public void validateSecretNames(String providerId, Set<String> names) {
        ChannelProviderDescriptor descriptor = require(providerId);
        Map<String, Field> fields = byName(descriptor.secrets());
        rejectUnknown(names, fields.keySet(), "secret");
        for (Field field : descriptor.secrets()) {
            if (field.required() && !names.contains(field.name())) {
                throw new IllegalArgumentException("Missing required secret: " + field.name());
            }
        }
    }

    /** Runtime requirements stay strict even when credentials come from OAuth or environment. */
    public Set<String> runtimeRequiredSecrets(String providerId) {
        return switch (normalizeProviderId(providerId)) {
            case "telegram", "discord" -> Set.of("botToken");
            case "slack" -> Set.of("botToken", "appToken");
            case "whatsapp" -> Set.of("accessToken", "verifyToken", "appSecret");
            case "email" -> Set.of("password");
            default -> Set.of();
        };
    }

    public void validateRuntimeSecrets(String providerId, Map<String, String> secrets) {
        Map<String, String> values = secrets == null ? Map.of() : secrets;
        for (String name : runtimeRequiredSecrets(providerId)) {
            if (values.get(name) == null || values.get(name).isBlank()) {
                throw new IllegalArgumentException("Missing required runtime credential: " + name);
            }
        }
    }

    private static Map<String, Field> byName(List<Field> fields) {
        Map<String, Field> result = new LinkedHashMap<>();
        fields.forEach(field -> result.put(field.name(), field));
        return result;
    }

    private static void rejectUnknown(Set<String> supplied, Set<String> known, String type) {
        for (String name : supplied) {
            if (!known.contains(name)) {
                throw new IllegalArgumentException("Unknown channel " + type + ": " + name);
            }
        }
    }

    private static Object convert(Field field, Object value) {
        try {
            return switch (field.type()) {
                case STRING -> String.valueOf(value).trim();
                case INTEGER -> value instanceof Number number
                        ? number.intValue()
                        : Integer.parseInt(String.valueOf(value).trim());
                case BOOLEAN -> booleanValue(value);
                case STRING_LIST -> stringList(value);
                case LONG_LIST -> longList(value);
            };
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Invalid value for " + field.name() + " (expected "
                            + field.type().name().toLowerCase(Locale.ROOT) + ")", e);
        }
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
            throw new IllegalArgumentException("expected true or false");
        }
        return Boolean.parseBoolean(text);
    }

    private static List<String> stringList(Object value) {
        Collection<?> values = value instanceof Collection<?> collection
                ? collection
                : List.of(String.valueOf(value).split(","));
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            String text = String.valueOf(item).trim();
            if (!text.isEmpty()) {
                result.add(text);
            }
        }
        return List.copyOf(result);
    }

    private static List<Long> longList(Object value) {
        return stringList(value).stream().map(Long::parseLong).toList();
    }

    private static boolean missing(Object value) {
        return value == null
                || value instanceof String text && text.isBlank()
                || value instanceof Collection<?> collection && collection.isEmpty();
    }

    private static void validateSemanticSettings(String providerId, Map<String, Object> settings) {
        if ("email".equals(providerId)) {
            requirePort(settings, "imapPort");
            requirePort(settings, "smtpPort");
            int poll = ((Number) settings.get("pollIntervalSeconds")).intValue();
            if (poll < 5 || poll > 3600) {
                throw new IllegalArgumentException(
                        "pollIntervalSeconds must be between 5 and 3600");
            }
        }
        if ("whatsapp".equals(providerId)
                && !String.valueOf(settings.get("phoneNumberId")).matches("[0-9]{5,32}")) {
            throw new IllegalArgumentException("phoneNumberId must contain 5-32 digits");
        }
    }

    private static void requirePort(Map<String, Object> settings, String name) {
        int port = ((Number) settings.get(name)).intValue();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(name + " must be between 1 and 65535");
        }
    }

    private static void register(
            Map<String, ChannelProviderDescriptor> target,
            ChannelProviderDescriptor descriptor) {
        if (target.putIfAbsent(descriptor.id(), descriptor) != null) {
            throw new IllegalStateException("Duplicate channel provider: " + descriptor.id());
        }
    }

    private static ChannelProviderDescriptor telegram() {
        return new ChannelProviderDescriptor(
                "telegram", "Telegram", "Telegram Bot API using long polling.",
                Set.of(Capability.INBOUND, Capability.OUTBOUND, Capability.POLLING),
                List.of(
                        field("allowedChatIds", "Allowed chat IDs", FieldType.LONG_LIST, false, List.of(),
                                "Chats allowed to invoke the agent. Empty denies inbound messages."),
                        allowAllField(),
                        allowHarnessSendField()),
                List.of(secret("botToken", "Bot token", true, "TELEGRAM_BOT_TOKEN")));
    }

    private static ChannelProviderDescriptor slack() {
        return new ChannelProviderDescriptor(
                "slack", "Slack", "Slack Web API with required Socket Mode inbound events.",
                Set.of(Capability.INBOUND, Capability.OUTBOUND, Capability.SOCKET),
                List.of(
                        field("useOAuth", "Use connected Slack OAuth", FieldType.BOOLEAN, false, false,
                                "Resolve the workspace bot token from Kompile OAuth. The Socket Mode app token remains separate."),
                        field("allowedChannelIds", "Allowed channel IDs", FieldType.STRING_LIST, false, List.of(),
                                "Slack channel IDs allowed to invoke the agent. Empty denies inbound messages."),
                        field("respondToAllMessages", "Respond to all messages", FieldType.BOOLEAN, false, false,
                                "When false, only app mentions invoke the agent."),
                        allowAllField(),
                        allowHarnessSendField()),
                List.of(
                        secret("botToken", "Bot token", false, "SLACK_BOT_TOKEN"),
                        secret("appToken", "Socket Mode app token", false, "SLACK_APP_TOKEN")));
    }

    private static ChannelProviderDescriptor discord() {
        return new ChannelProviderDescriptor(
                "discord", "Discord", "Discord Bot Gateway and REST API.",
                Set.of(Capability.INBOUND, Capability.OUTBOUND, Capability.SOCKET),
                List.of(
                        field("allowedChannelIds", "Allowed channel IDs", FieldType.STRING_LIST, false, List.of(),
                                "Discord channels allowed to invoke the agent."),
                        field("allowedGuildIds", "Allowed guild IDs", FieldType.STRING_LIST, false, List.of(),
                                "Discord guilds allowed to invoke the agent."),
                        allowAllField(),
                        allowHarnessSendField()),
                List.of(secret("botToken", "Bot token", true, "DISCORD_BOT_TOKEN")));
    }

    private static ChannelProviderDescriptor whatsapp() {
        return new ChannelProviderDescriptor(
                "whatsapp", "WhatsApp", "Meta WhatsApp Business Cloud API.",
                Set.of(Capability.INBOUND, Capability.OUTBOUND, Capability.WEBHOOK),
                List.of(
                        field("phoneNumberId", "Phone number ID", FieldType.STRING, true, null,
                                "Meta WhatsApp Business phone number ID."),
                        field("allowedPhoneNumbers", "Allowed phone numbers", FieldType.STRING_LIST, false, List.of(),
                                "Phone numbers allowed to invoke the agent."),
                        allowAllField(),
                        allowHarnessSendField()),
                List.of(
                        secret("accessToken", "Access token", true, "WHATSAPP_ACCESS_TOKEN"),
                        secret("verifyToken", "Webhook verify token", true, "WHATSAPP_VERIFY_TOKEN"),
                        secret("appSecret", "Meta app secret", true, "WHATSAPP_APP_SECRET")));
    }

    private static ChannelProviderDescriptor email() {
        return new ChannelProviderDescriptor(
                "email", "Email", "IMAP inbox polling with SMTP replies and outbound mail.",
                Set.of(Capability.INBOUND, Capability.OUTBOUND, Capability.POLLING),
                List.of(
                        field("imapHost", "IMAP host", FieldType.STRING, true, null, "IMAP server hostname."),
                        field("imapPort", "IMAP port", FieldType.INTEGER, false, 993, "IMAP server port."),
                        field("smtpHost", "SMTP host", FieldType.STRING, true, null, "SMTP server hostname."),
                        field("smtpPort", "SMTP port", FieldType.INTEGER, false, 587, "SMTP server port."),
                        field("username", "Username", FieldType.STRING, true, null, "Mailbox login name."),
                        field("fromAddress", "From address", FieldType.STRING, true, null, "Address used for outbound mail."),
                        field("fromName", "From name", FieldType.STRING, false, "Kompile", "Display name for outbound mail."),
                        field("trustedAuthenticationServer", "Trusted Authentication-Results server",
                                FieldType.STRING, true, null,
                                "Authserv-id prepended by the receiving MTA (for example mx.google.com)."),
                        field("pollIntervalSeconds", "Poll interval", FieldType.INTEGER, false, 60, "Inbox poll interval in seconds."),
                        field("allowedSenders", "Allowed senders", FieldType.STRING_LIST, false, List.of(),
                                "Exact sender addresses allowed to invoke the agent."),
                        allowAllField(),
                        allowHarnessSendField()),
                List.of(secret("password", "Password or app password", true, "KOMPILE_EMAIL_PASSWORD")));
    }

    private static Field allowAllField() {
        return field("allowAllInbound", "Allow all inbound senders", FieldType.BOOLEAN, false, false,
                "Explicitly opt in to accepting messages outside an allowlist.");
    }

    private static Field allowHarnessSendField() {
        return field("allowHarnessSend", "Allow harness delivery", FieldType.BOOLEAN, false, false,
                "Allow the chat harness to send only to targets in this connection's explicit allowlist.");
    }

    private static Field field(
            String name,
            String label,
            FieldType type,
            boolean required,
            Object defaultValue,
            String description) {
        return new Field(name, label, type, required, defaultValue, description, null);
    }

    private static Field secret(String name, String label, boolean required, String environmentHint) {
        return new Field(name, label, FieldType.STRING, required, null,
                "Secret value; accepted only on write operations.", environmentHint);
    }
}
