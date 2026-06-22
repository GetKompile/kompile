/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.crawl.graph.preprocessing;

import ai.kompile.core.crawl.graph.DocumentPreprocessor;
import ai.kompile.core.crawl.graph.PreprocessingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Source-specific normalization for SPREADSHEET / CSV / TSV documents.
 *
 * <p>Applies only when the document carries a {@code documentType} of
 * {@code csv}, {@code tsv}, {@code spreadsheet}, or {@code table}, or when
 * the content type indicates a spreadsheet MIME type.</p>
 *
 * <p>Tasks performed:</p>
 * <ol>
 *   <li><b>Header normalization</b> — canonicalize column names: trim whitespace,
 *       lowercase, replace spaces/special chars with underscores, deduplicate
 *       by appending {@code _2}, {@code _3}, etc.</li>
 *   <li><b>Empty row removal</b> — drop rows that contain no non-whitespace
 *       cell values (e.g. blank separators between table sections).</li>
 *   <li><b>Cell type coercion hints</b> — annotate clearly numeric or boolean
 *       cells so the LLM extraction prompt receives typed data rather than
 *       ambiguous strings (e.g. replaces common boolean synonyms with
 *       {@code true}/{@code false}).</li>
 *   <li><b>Trailing whitespace trimming</b> — strip trailing spaces from each
 *       cell to avoid embedding noise.</li>
 * </ol>
 *
 * <p>The preprocessor operates on text that has already been extracted by the
 * Tika loader — i.e., the document text is expected to be a delimited
 * representation (CSV/TSV rows) or a Tika-rendered table.</p>
 *
 * <p>Order: 305 — runs in the content-filtering band, before boilerplate
 * removal (310) and deduplication (400), so that blank rows are gone before
 * the dedup hash is computed.</p>
 *
 * <p>No-ops on non-spreadsheet documents.</p>
 */
@Component
public class SpreadsheetNormalizationPreprocessor implements DocumentPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(SpreadsheetNormalizationPreprocessor.class);

    // ── Step ID ───────────────────────────────────────────────────────────────
    static final String STEP_ID = "source-normalization-spreadsheet";

    // ── Source-type / documentType values ─────────────────────────────────────
    private static final List<String> SPREADSHEET_DOC_TYPES = List.of(
            "csv", "tsv", "spreadsheet", "table",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/csv", "text/tab-separated-values"
    );

    // ── Cell normalization ─────────────────────────────────────────────────────

    /** Characters in column headers that are replaced by an underscore. */
    private static final Pattern HEADER_SPECIAL_CHARS = Pattern.compile("[^a-z0-9]+");

    /** Common boolean synonym → canonical value mappings (case-insensitive). */
    private static final List<String[]> BOOLEAN_SYNONYMS = List.of(
            new String[]{"yes", "true"},
            new String[]{"no", "false"},
            new String[]{"y", "true"},
            new String[]{"n", "false"},
            new String[]{"1", "true"},
            new String[]{"0", "false"},
            new String[]{"on", "true"},
            new String[]{"off", "false"},
            new String[]{"enabled", "true"},
            new String[]{"disabled", "false"}
    );

    /** Regex to detect a purely numeric cell (integer or decimal, optional sign). */
    private static final Pattern NUMERIC_CELL = Pattern.compile(
            "^[+-]?\\d{1,3}(?:[,_]\\d{3})*(?:\\.\\d+)?$|^[+-]?\\.\\d+$");

    /** Thousand-separator removal (commas used as thousands separators). */
    private static final Pattern THOUSANDS_SEP = Pattern.compile("(?<=\\d),(?=\\d{3}(?:[^\\d]|$))");

    // ── CSV/TSV parsing ────────────────────────────────────────────────────────

    /** Detects comma-separated format (≥2 commas in the first non-empty line). */
    private static final Pattern CSV_SNIFFER = Pattern.compile(",");
    /** Detects tab-separated format. */
    private static final Pattern TSV_SNIFFER = Pattern.compile("\t");

    @Override
    public String id() {
        return STEP_ID;
    }

    @Override
    public String displayName() {
        return "Spreadsheet / CSV Source Normalization";
    }

    @Override
    public int order() {
        return 305;
    }

    @Override
    public boolean appliesTo(Document document, PreprocessingConfig config) {
        // Gate: reuse boilerplateRemoval enabled flag (same reasoning as EmailNormalizationPreprocessor).
        if (config.getBoilerplateRemoval() == null || !config.getBoilerplateRemoval().isEnabled()) {
            return false;
        }
        return isSpreadsheetDocument(document);
    }

    @Override
    public List<Document> process(List<Document> documents, PreprocessingConfig config) {
        int minRemaining = config.getBoilerplateRemoval() != null
                ? config.getBoilerplateRemoval().getMinRemainingChars()
                : 50;

        List<Document> result = new ArrayList<>(documents.size());
        int modified = 0;

        for (Document doc : documents) {
            if (Thread.currentThread().isInterrupted()) {
                result.add(doc);
                continue;
            }

            String text = doc.getText();
            if (text == null || text.isBlank()) {
                result.add(doc);
                continue;
            }

            String cleaned = normalizeSpreadsheetText(text);
            if (!cleaned.equals(text) && cleaned.length() >= minRemaining) {
                Document out = new Document(cleaned);
                out.getMetadata().putAll(doc.getMetadata());
                out.getMetadata().put("spreadsheet_normalized", true);
                out.getMetadata().put("spreadsheet_normalization_chars_removed", text.length() - cleaned.length());
                result.add(out);
                modified++;
            } else {
                result.add(doc);
            }
        }

        log.debug("Spreadsheet normalization: {}/{} documents modified", modified, documents.size());
        return result;
    }

    // ── Core normalization logic ───────────────────────────────────────────────

    /**
     * Detects the delimiter, normalizes headers, drops blank rows, coerces
     * cell types, and returns the cleaned table text.
     */
    static String normalizeSpreadsheetText(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length == 0) return text;

        // Sniff delimiter from first non-empty line
        String firstLine = Arrays.stream(lines)
                .filter(l -> !l.isBlank())
                .findFirst()
                .orElse(lines[0]);

        long tabCount  = TSV_SNIFFER.matcher(firstLine).results().count();
        long commaCount = CSV_SNIFFER.matcher(firstLine).results().count();

        String delimiter = tabCount > commaCount ? "\t" : ",";

        // First non-blank line = header
        int headerIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) return text; // all blank

        String[] rawHeaders = splitLine(lines[headerIdx], delimiter);
        String[] normHeaders = normalizeHeaders(rawHeaders);

        List<String> output = new ArrayList<>();
        output.add(String.join(delimiter, normHeaders));

        for (int i = headerIdx + 1; i < lines.length; i++) {
            String line = lines[i];
            // Drop empty rows
            if (isEmptyRow(line, delimiter)) continue;

            String[] cells = splitLine(line, delimiter);
            String[] coerced = coerceCells(cells);
            output.add(String.join(delimiter, coerced));
        }

        return String.join("\n", output);
    }

    /**
     * Normalizes column headers: trim, lowercase, replace non-alphanumeric runs
     * with underscore, deduplicate by appending {@code _2}, {@code _3}, etc.
     */
    static String[] normalizeHeaders(String[] raw) {
        String[] result = new String[raw.length];
        List<String> seen = new ArrayList<>();

        for (int i = 0; i < raw.length; i++) {
            String h = raw[i].trim().toLowerCase();
            h = HEADER_SPECIAL_CHARS.matcher(h).replaceAll("_");
            h = h.replaceAll("^_+|_+$", ""); // trim leading/trailing underscores
            if (h.isEmpty()) h = "col_" + (i + 1);

            // Dedup
            String base = h;
            int count = 2;
            while (seen.contains(h)) {
                h = base + "_" + count++;
            }
            seen.add(h);
            result[i] = h;
        }
        return result;
    }

    /**
     * Returns true when every cell in the row is blank.
     */
    static boolean isEmptyRow(String line, String delimiter) {
        String[] cells = splitLine(line, delimiter);
        for (String cell : cells) {
            if (!cell.trim().isEmpty()) return false;
        }
        return true;
    }

    /**
     * Trims cell whitespace and coerces obvious boolean synonyms to
     * {@code true}/{@code false}. Numeric cells have thousand-separators removed
     * so downstream extraction parses them as numbers.
     */
    static String[] coerceCells(String[] cells) {
        String[] result = new String[cells.length];
        for (int i = 0; i < cells.length; i++) {
            String cell = cells[i].trim();
            // Unwrap surrounding quotes (simple case: "value")
            if (cell.startsWith("\"") && cell.endsWith("\"") && cell.length() >= 2) {
                cell = cell.substring(1, cell.length() - 1).replace("\"\"", "\"");
            }
            // Boolean coercion
            String lower = cell.toLowerCase();
            boolean boolMatch = false;
            for (String[] pair : BOOLEAN_SYNONYMS) {
                if (pair[0].equals(lower)) {
                    cell = pair[1];
                    boolMatch = true;
                    break;
                }
            }
            // Numeric normalization (only if not already mapped to boolean). Test the ORIGINAL
            // cell against NUMERIC_CELL — that pattern recognizes thousand-separated grouping
            // (e.g. 1,234,567); only then strip the separators. Testing the stripped form would
            // fail because NUMERIC_CELL requires the grouping to be present, not a bare digit run.
            if (!boolMatch && NUMERIC_CELL.matcher(cell).matches()) {
                cell = THOUSANDS_SEP.matcher(cell).replaceAll("");
            }
            result[i] = cell;
        }
        return result;
    }

    /**
     * Simple delimiter split that does NOT handle RFC-4180 quoted fields
     * containing the delimiter — good enough for the normalization pass.
     * (Full RFC-4180 parsing is the loader's responsibility.)
     */
    static String[] splitLine(String line, String delimiter) {
        return line.split(Pattern.quote(delimiter), -1);
    }

    // ── Source-type detection ─────────────────────────────────────────────────

    private static boolean isSpreadsheetDocument(Document doc) {
        Object docType    = doc.getMetadata().get("documentType");
        Object contentType = doc.getMetadata().get("content_type");
        Object hint       = doc.getMetadata().get("content_type_hint");

        for (String known : SPREADSHEET_DOC_TYPES) {
            if (known.equals(docType) || known.equals(contentType) || known.equals(hint)) {
                return true;
            }
        }
        return false;
    }
}
