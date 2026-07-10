/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Function;

/**
 * Tolerant parser for event timestamps extracted from heterogeneous graph sources.
 *
 * <p>Offset-aware inputs are normalized to UTC before their offset is removed. Inputs without
 * an offset retain their declared local wall time. Invalid or blank values return {@code null}.
 */
public final class OccurredAtParser {

    private static final List<DateTimeFormatter> LOCAL_DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS][.SSSSSS][.SSSSSSSSS]"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy h:mm[:ss] a"));

    private OccurredAtParser() {
    }

    public static LocalDateTime parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();

        LocalDateTime parsed = attempt(value,
                v -> OffsetDateTime.parse(v).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
        if (parsed != null) {
            return parsed;
        }
        parsed = attempt(value,
                v -> ZonedDateTime.parse(v).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
        if (parsed != null) {
            return parsed;
        }
        parsed = attempt(value,
                v -> LocalDateTime.ofInstant(Instant.parse(v), ZoneOffset.UTC));
        if (parsed != null) {
            return parsed;
        }
        parsed = attempt(value, LocalDateTime::parse);
        if (parsed != null) {
            return parsed;
        }
        parsed = attempt(value,
                v -> ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .withZoneSameInstant(ZoneOffset.UTC)
                        .toLocalDateTime());
        if (parsed != null) {
            return parsed;
        }
        for (DateTimeFormatter formatter : LOCAL_DATE_TIME_FORMATS) {
            parsed = attempt(value, v -> LocalDateTime.parse(v, formatter));
            if (parsed != null) {
                return parsed;
            }
        }
        parsed = attempt(value, v -> LocalDate.parse(v).atStartOfDay());
        if (parsed != null) {
            return parsed;
        }
        return parseEpoch(value);
    }

    private static LocalDateTime parseEpoch(String value) {
        if (!value.matches("-?[0-9]{10,17}")) {
            return null;
        }
        try {
            long epoch = Long.parseLong(value);
            Instant instant = Math.abs(epoch) < 100_000_000_000L
                    ? Instant.ofEpochSecond(epoch)
                    : Instant.ofEpochMilli(epoch);
            return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static LocalDateTime attempt(
            String value, Function<String, LocalDateTime> parser) {
        try {
            return parser.apply(value);
        } catch (DateTimeParseException | ArithmeticException ignored) {
            return null;
        }
    }
}
