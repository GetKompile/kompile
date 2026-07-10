/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol.grounding.calibration;

import ai.kompile.graph.reasoning.fol.grounding.PlattCalibrator;
import ai.kompile.graph.reasoning.fol.grounding.StrengthCalibrator.LabeledScore;
import ai.kompile.graph.reasoning.fol.grounding.StrengthCalibrator.SignalType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Multi-signal calibration harness that manages both Platt (sigmoid) and
 * isotonic regression calibrators per {@link SignalType}.
 *
 * <h2>Usage</h2>
 * <ol>
 *   <li>Accumulate examples: {@link #addSample(SignalType, double, boolean)}.</li>
 *   <li>Fit either or both calibrator families:
 *       {@link #fitPlatt(SignalType)}, {@link #fitIsotonic(SignalType)}.</li>
 *   <li>Query calibration quality: {@link #ece(SignalType, int)},
 *       {@link #reliabilityTable(SignalType, int)}.</li>
 *   <li>Persist and restore: {@link #toJson()} / {@link #fromJson(String)}.</li>
 * </ol>
 *
 * <h2>Calibration priority</h2>
 * <p>When both Platt and isotonic calibrators are fitted for a signal type,
 * the isotonic calibrator is preferred for ECE / reliability queries (it is
 * generally better-calibrated in low-data regimes). If neither is fitted,
 * the raw score is used as-is.</p>
 */
public class CalibrationHarness {

    /**
     * Raw sample accumulator per signal type.
     * Each {@code double[2]} = {rawScore, outcome (0.0 or 1.0)}.
     */
    private final Map<SignalType, List<double[]>> samples = new EnumMap<>(SignalType.class);

    /** Isotonic calibrators per signal type (populated after {@link #fitIsotonic(SignalType)}). */
    private final Map<SignalType, IsotonicCalibrator> isotonicCals = new EnumMap<>(SignalType.class);

    /**
     * Shared Platt calibrator that stores per-type (w, b) parameters.
     * Populated after {@link #fitPlatt(SignalType)}.
     */
    private final PlattCalibrator plattCal = new PlattCalibrator();

    /** Tracks which SignalTypes have had Platt fitting applied. */
    private final Set<SignalType> plattFitted = EnumSet.noneOf(SignalType.class);

    // ─── Sample accumulation ─────────────────────────────────────────────────────

    /**
     * Add one labeled calibration sample for the given signal type.
     *
     * @param signalType the signal source (PSL, MEBN, etc.)
     * @param rawScore   the engine's raw output score
     * @param outcome    true = positive / supported; false = negative / refuted
     */
    public void addSample(SignalType signalType, double rawScore, boolean outcome) {
        samples.computeIfAbsent(signalType, k -> new ArrayList<>())
               .add(new double[]{rawScore, outcome ? 1.0 : 0.0});
    }

    // ─── Fitting ─────────────────────────────────────────────────────────────────

    /**
     * Fit a Platt scaling calibrator for the given signal type using all samples
     * accumulated so far via {@link #addSample(SignalType, double, boolean)}.
     *
     * <p>Delegates to {@link PlattCalibrator#updateFromLabeledBatch(SignalType, List)}
     * which runs 100 steps of gradient descent on log-loss, then marks this type
     * as Platt-fitted.</p>
     *
     * @param signalType the signal type to calibrate
     * @throws IllegalStateException if no samples have been added for this type
     */
    public void fitPlatt(SignalType signalType) {
        List<double[]> raw = samples.getOrDefault(signalType, List.of());
        if (raw.isEmpty()) {
            throw new IllegalStateException(
                    "No samples for SignalType " + signalType + " — cannot fit Platt calibrator");
        }
        List<LabeledScore> batch = new ArrayList<>(raw.size());
        for (double[] s : raw) {
            batch.add(new LabeledScore(s[0], s[1]));
        }
        plattCal.updateFromLabeledBatch(signalType, batch);
        plattFitted.add(signalType);
    }

    /**
     * Fit an isotonic regression calibrator for the given signal type using all
     * samples accumulated so far via {@link #addSample(SignalType, double, boolean)}.
     *
     * @param signalType the signal type to calibrate
     * @throws IllegalStateException if no samples have been added for this type
     */
    public void fitIsotonic(SignalType signalType) {
        List<double[]> raw = samples.getOrDefault(signalType, List.of());
        if (raw.isEmpty()) {
            throw new IllegalStateException(
                    "No samples for SignalType " + signalType + " — cannot fit IsotonicCalibrator");
        }
        IsotonicCalibrator cal = new IsotonicCalibrator();
        for (double[] s : raw) {
            cal.addSample(s[0], s[1] >= 0.5);
        }
        cal.fit();
        isotonicCals.put(signalType, cal);
    }

    // ─── Calibration queries ─────────────────────────────────────────────────────

    /**
     * Calibrate a single raw score for the given signal type using the best
     * available calibrator (isotonic → Platt → identity).
     *
     * @param signalType the signal type
     * @param rawScore   the raw engine score
     * @return calibrated probability in [0, 1]
     */
    public double calibrate(SignalType signalType, double rawScore) {
        IsotonicCalibrator iso = isotonicCals.get(signalType);
        if (iso != null) {
            return iso.calibrate(rawScore);
        }
        if (plattFitted.contains(signalType)) {
            double[] wb = plattCal.getParams(signalType);
            double clamped = Math.max(0.0, Math.min(1.0, rawScore));
            return sigmoid(wb[0] * clamped + wb[1]);
        }
        // Identity: clamp raw score to [0,1]
        return Math.max(0.0, Math.min(1.0, rawScore));
    }

    // ─── Calibration metrics ─────────────────────────────────────────────────────

    /**
     * Expected Calibration Error (ECE) for a signal type, computed over the
     * CALIBRATED scores from the best available calibrator.
     *
     * <p>Partitions samples into {@code bins} equal-width bins over [0,1], computes
     * |mean_confidence − accuracy| for each non-empty bin, then returns the
     * sample-weighted average gap.</p>
     *
     * @param signalType the signal type to evaluate
     * @param bins       number of equal-width bins (must be ≥ 1)
     * @return ECE in [0, 1], or 0.0 if there are no samples
     */
    public double ece(SignalType signalType, int bins) {
        if (bins < 1) throw new IllegalArgumentException("bins must be >= 1");
        List<double[]> raw = samples.getOrDefault(signalType, List.of());
        if (raw.isEmpty()) return 0.0;

        // Build bin accumulators: [sumConf, sumOutcome, count] per bin
        double[][] binData = new double[bins][3];

        for (double[] s : raw) {
            double calibrated = calibrate(signalType, s[0]);
            double outcome    = s[1];
            int binIdx = Math.min(bins - 1, (int) (calibrated * bins));
            binData[binIdx][0] += calibrated;
            binData[binIdx][1] += outcome;
            binData[binIdx][2] += 1.0;
        }

        double ece = 0.0;
        int total  = raw.size();
        for (double[] bin : binData) {
            double count = bin[2];
            if (count == 0) continue;
            double meanConf = bin[0] / count;
            double accuracy = bin[1] / count;
            ece += (count / total) * Math.abs(meanConf - accuracy);
        }
        return ece;
    }

    /**
     * Reliability table for a signal type, partitioned into {@code bins} equal-width
     * bins over [0,1] on the CALIBRATED scores.
     *
     * @param signalType the signal type
     * @param bins       number of equal-width bins (must be ≥ 1)
     * @return list of {@code double[3]} rows: {meanConfidence, accuracy, sampleCount};
     *         only non-empty bins are included
     */
    public List<double[]> reliabilityTable(SignalType signalType, int bins) {
        if (bins < 1) throw new IllegalArgumentException("bins must be >= 1");
        List<double[]> raw = samples.getOrDefault(signalType, List.of());

        double[][] binData = new double[bins][3]; // [sumConf, sumOutcome, count]
        for (double[] s : raw) {
            double calibrated = calibrate(signalType, s[0]);
            int binIdx = Math.min(bins - 1, (int) (calibrated * bins));
            binData[binIdx][0] += calibrated;
            binData[binIdx][1] += s[1];
            binData[binIdx][2] += 1.0;
        }

        List<double[]> table = new ArrayList<>();
        for (double[] bin : binData) {
            if (bin[2] == 0) continue;
            table.add(new double[]{bin[0] / bin[2], bin[1] / bin[2], bin[2]});
        }
        return table;
    }

    // ─── Serialization ───────────────────────────────────────────────────────────

    /**
     * Serialize the fitted calibration state to a compact JSON string.
     *
     * <p>Format (hand-rolled; no jackson-databind):
     * <pre>
     * {
     *   "isotonic": { "PSL_SOFT_TRUTH": "x1:y1,x2:y2,...", ... },
     *   "platt": { "MEBN_POSTERIOR": [w, b], ... }
     * }
     * </pre>
     *
     * @return JSON string representing fitted calibrators; empty objects if nothing fitted
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');

        // Isotonic section
        sb.append("\"isotonic\":{");
        boolean first = true;
        for (Map.Entry<SignalType, IsotonicCalibrator> e : isotonicCals.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey().name()).append("\":\"")
              .append(escapeJson(e.getValue().serialize())).append('"');
        }
        sb.append('}');

        sb.append(',');

        // Platt section
        sb.append("\"platt\":{");
        first = true;
        for (SignalType st : plattFitted) {
            if (!first) sb.append(',');
            first = false;
            double[] wb = plattCal.getParams(st);
            sb.append('"').append(st.name()).append("\":[")
              .append(wb[0]).append(',').append(wb[1]).append(']');
        }
        sb.append('}');

        sb.append('}');
        return sb.toString();
    }

    /**
     * Restore a {@link CalibrationHarness} from a JSON string produced by {@link #toJson()}.
     *
     * <p>Does not restore raw samples — only the fitted calibrators.
     * The returned harness can be used immediately for {@link #calibrate} calls
     * but will throw on {@link #fitPlatt}/{@link #fitIsotonic} (no samples loaded).</p>
     *
     * @param json JSON string in the format produced by {@link #toJson()}
     * @return a harness with fitted calibrators
     * @throws IllegalArgumentException if the JSON is malformed
     */
    public static CalibrationHarness fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("CalibrationHarness.fromJson: null/blank JSON");
        }
        CalibrationHarness h = new CalibrationHarness();

        // Parse outer object manually (two keys: isotonic, platt)
        // Strip outer braces
        String s = json.trim();
        if (s.startsWith("{")) s = s.substring(1);
        if (s.endsWith("}"))   s = s.substring(0, s.length() - 1);

        // Split top-level keys
        Map<String, String> top = parseTopLevelKeys(s);

        // Isotonic section
        String isoSection = top.get("isotonic");
        if (isoSection != null && !isoSection.isBlank()) {
            String inner = isoSection.trim();
            if (inner.startsWith("{")) inner = inner.substring(1);
            if (inner.endsWith("}"))   inner = inner.substring(0, inner.length() - 1);
            Map<String, String> isoEntries = parseStringValueMap(inner);
            for (Map.Entry<String, String> e : isoEntries.entrySet()) {
                try {
                    SignalType st = SignalType.valueOf(e.getKey());
                    h.isotonicCals.put(st, IsotonicCalibrator.deserialize(e.getValue()));
                } catch (IllegalArgumentException ignored) {
                    // Unknown signal type in JSON: skip gracefully
                }
            }
        }

        // Platt section
        String plattSection = top.get("platt");
        if (plattSection != null && !plattSection.isBlank()) {
            String inner = plattSection.trim();
            if (inner.startsWith("{")) inner = inner.substring(1);
            if (inner.endsWith("}"))   inner = inner.substring(0, inner.length() - 1);
            Map<String, String> plattEntries = parseArrayValueMap(inner);
            for (Map.Entry<String, String> e : plattEntries.entrySet()) {
                try {
                    SignalType st = SignalType.valueOf(e.getKey());
                    double[] wb = parseDoubleArray(e.getValue());
                    if (wb.length == 2) {
                        h.plattCal.setParams(st, wb[0], wb[1]);
                        h.plattFitted.add(st);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Unknown signal type or bad array: skip gracefully
                }
            }
        }

        return h;
    }

    // ─── Accessors (for testing) ──────────────────────────────────────────────────

    /** True if an isotonic calibrator has been fitted for this type. */
    public boolean isIsotonicFitted(SignalType st) {
        return isotonicCals.containsKey(st);
    }

    /** True if a Platt calibrator has been fitted for this type. */
    public boolean isPlattFitted(SignalType st) {
        return plattFitted.contains(st);
    }

    /** Number of raw samples accumulated for this type. */
    public int sampleCount(SignalType st) {
        return samples.getOrDefault(st, List.of()).size();
    }

    // ─── Private JSON helpers ─────────────────────────────────────────────────────

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Parse a flat JSON object where all values are either quoted strings or
     * nested JSON objects. Returns top-level key → raw-value-string map.
     * This is a minimal parser sufficient for the two-key format we produce.
     */
    private static Map<String, String> parseTopLevelKeys(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0, n = body.length();
        while (i < n) {
            while (i < n && body.charAt(i) <= ' ') i++;
            if (i >= n || body.charAt(i) != '"') break;
            // read key
            int ks = i + 1;
            i = nextUnescapedQuote(body, ks);
            String key = body.substring(ks, i);
            i++; // skip closing "
            // skip whitespace and colon
            while (i < n && (body.charAt(i) <= ' ' || body.charAt(i) == ':')) i++;
            // read value: could be '{...}' or '"..."'
            if (i >= n) break;
            char ch = body.charAt(i);
            String value;
            if (ch == '{') {
                value = extractBalanced(body, i, '{', '}');
                i += value.length();
            } else if (ch == '"') {
                int vs = i + 1;
                i = nextUnescapedQuote(body, vs);
                value = body.substring(vs, i);
                i++; // skip closing "
            } else {
                // bare value until comma
                int start = i;
                while (i < n && body.charAt(i) != ',') i++;
                value = body.substring(start, i).trim();
            }
            out.put(key, value);
            // skip comma
            while (i < n && (body.charAt(i) <= ' ' || body.charAt(i) == ',')) i++;
        }
        return out;
    }

    /** Parse a JSON object where all values are quoted strings. */
    private static Map<String, String> parseStringValueMap(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0, n = body.length();
        while (i < n) {
            while (i < n && body.charAt(i) <= ' ') i++;
            if (i >= n || body.charAt(i) != '"') break;
            int ks = i + 1;
            i = nextUnescapedQuote(body, ks);
            String key = body.substring(ks, i);
            i++;
            while (i < n && (body.charAt(i) <= ' ' || body.charAt(i) == ':')) i++;
            if (i >= n || body.charAt(i) != '"') break;
            int vs = i + 1;
            i = nextUnescapedQuote(body, vs);
            String value = body.substring(vs, i);
            i++;
            out.put(key, unescapeJson(value));
            while (i < n && (body.charAt(i) <= ' ' || body.charAt(i) == ',')) i++;
        }
        return out;
    }

    /** Parse a JSON object where all values are JSON arrays like [1.0,2.0]. */
    private static Map<String, String> parseArrayValueMap(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0, n = body.length();
        while (i < n) {
            while (i < n && body.charAt(i) <= ' ') i++;
            if (i >= n || body.charAt(i) != '"') break;
            int ks = i + 1;
            i = nextUnescapedQuote(body, ks);
            String key = body.substring(ks, i);
            i++;
            while (i < n && (body.charAt(i) <= ' ' || body.charAt(i) == ':')) i++;
            if (i >= n || body.charAt(i) != '[') break;
            String arr = extractBalanced(body, i, '[', ']');
            out.put(key, arr);
            i += arr.length();
            while (i < n && (body.charAt(i) <= ' ' || body.charAt(i) == ',')) i++;
        }
        return out;
    }

    private static double[] parseDoubleArray(String s) {
        // Strip brackets
        String inner = s.trim();
        if (inner.startsWith("[")) inner = inner.substring(1);
        if (inner.endsWith("]"))   inner = inner.substring(0, inner.length() - 1);
        String[] parts = inner.split(",");
        double[] result = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Double.parseDouble(parts[i].trim());
        }
        return result;
    }

    /** Extract a balanced-bracket substring starting at {@code start}. */
    private static String extractBalanced(String s, int start, char open, char close) {
        int depth = 0, i = start, n = s.length();
        boolean inStr = false;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"') inStr = !inStr;
            else if (!inStr && c == open)  depth++;
            else if (!inStr && c == close) { depth--; if (depth == 0) { i++; break; } }
            i++;
        }
        return s.substring(start, i);
    }

    /** Find next unescaped double-quote at or after {@code start}. */
    private static int nextUnescapedQuote(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '\\') { i++; continue; }
            if (s.charAt(i) == '"') return i;
        }
        return s.length();
    }

    private static String unescapeJson(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }
}
