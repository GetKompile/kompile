/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.channel;

import ai.kompile.channel.api.ChannelProviderDescriptor;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Resolves channel secrets without ever accepting literal secret values on the command line. */
final class SecretInputResolver {

    interface SecretPrompter {
        char[] readPassword(String prompt);
    }

    private final BufferedReader input;
    private final Function<String, String> environment;
    private final SecretPrompter prompter;

    SecretInputResolver(
            InputStream input,
            Function<String, String> environment,
            SecretPrompter prompter) {
        this.input = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.environment = environment;
        this.prompter = prompter;
    }

    static SecretInputResolver system() {
        Console console = System.console();
        return new SecretInputResolver(
                System.in,
                System::getenv,
                console == null ? null : console::readPassword);
    }

    Map<String, String> resolve(
            ChannelProviderDescriptor provider,
            Map<String, String> environmentReferences,
            Map<String, Path> fileReferences,
            List<String> stdinFields,
            boolean promptRequired) throws IOException {
        Set<String> known = provider.secrets().stream()
                .map(ChannelProviderDescriptor.Field::name)
                .collect(Collectors.toSet());
        Map<String, String> result = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : safe(environmentReferences).entrySet()) {
            requireKnown(known, entry.getKey());
            String value = environment.apply(entry.getValue());
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "Environment variable is not set: " + entry.getValue());
            }
            putOnce(result, entry.getKey(), value);
        }
        for (Map.Entry<String, Path> entry : safePaths(fileReferences).entrySet()) {
            requireKnown(known, entry.getKey());
            String value = Files.readString(entry.getValue(), StandardCharsets.UTF_8).trim();
            putOnce(result, entry.getKey(), value);
        }
        for (String field : stdinFields == null ? List.<String>of() : stdinFields) {
            requireKnown(known, field);
            String value = input.readLine();
            if (value == null) {
                throw new IllegalArgumentException("No standard-input value was available for " + field);
            }
            putOnce(result, field, value);
        }

        if (promptRequired) {
            for (ChannelProviderDescriptor.Field field : provider.secrets()) {
                if (!field.required() || result.containsKey(field.name())) {
                    continue;
                }
                if (prompter == null) {
                    throw new IllegalArgumentException(
                            "Missing required secret " + field.name()
                                    + "; use --secret-from-env, --secret-file, or --secret-stdin");
                }
                char[] secret = prompter.readPassword(field.label() + ": ");
                try {
                    if (secret == null || secret.length == 0) {
                        throw new IllegalArgumentException("Secret must not be blank: " + field.name());
                    }
                    putOnce(result, field.name(), new String(secret));
                } finally {
                    if (secret != null) {
                        Arrays.fill(secret, '\0');
                    }
                }
            }
        }
        result.forEach((name, value) -> {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Secret must not be blank: " + name);
            }
        });
        return Map.copyOf(result);
    }

    private static void putOnce(Map<String, String> target, String name, String value) {
        if (target.putIfAbsent(name, value) != null) {
            throw new IllegalArgumentException("Secret field was supplied more than once: " + name);
        }
    }

    private static void requireKnown(Set<String> known, String name) {
        if (!known.contains(name)) {
            throw new IllegalArgumentException("Unknown channel secret: " + name);
        }
    }

    private static Map<String, String> safe(Map<String, String> values) {
        return values == null ? Map.of() : values;
    }

    private static Map<String, Path> safePaths(Map<String, Path> values) {
        return values == null ? Map.of() : values;
    }
}
