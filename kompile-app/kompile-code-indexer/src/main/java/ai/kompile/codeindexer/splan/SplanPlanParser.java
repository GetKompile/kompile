/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.codeindexer.splan;

import java.util.*;

/**
 * High-level API for parsing splan documents into a structured Plan object.
 * Wraps the ANTLR-generated parser with a convenient Java API.
 *
 * Usage:
 * <pre>
 *   Plan plan = SplanPlanParser.parse(splanText);
 *   for (Operation op : plan.operations()) {
 *       System.out.println(op.command() + " " + op.arguments());
 *   }
 * </pre>
 */
public class SplanPlanParser {

    /** A parsed splan plan */
    public record Plan(
            List<Section> sections,
            List<String> comments
    ) {
        public List<Operation> allOperations() {
            return sections.stream()
                    .flatMap(s -> s.operations().stream())
                    .toList();
        }
    }

    /** A section in a plan (separated by ---) */
    public record Section(
            int index,
            List<Operation> operations,
            Map<String, String> declarations
    ) {}

    /** A single operation: command + arguments */
    public record Operation(
            String command,
            List<Argument> arguments,
            int lineNumber
    ) {}

    /** An argument to an operation */
    public sealed interface Argument {
        record Token(String value) implements Argument {}
        record DeclRef(String name) implements Argument {}
        record ContentBlock(String content, String delimiter) implements Argument {}
    }

    /**
     * Parse an splan document from text. This is a hand-written recursive-descent
     * parser that follows the splan EBNF exactly, serving as a fallback when the
     * ANTLR runtime is not on the classpath.
     */
    public static Plan parse(String input) {
        List<Section> sections = new ArrayList<>();
        List<String> comments = new ArrayList<>();

        Section currentSection = new Section(0, new ArrayList<>(), new LinkedHashMap<>());
        int sectionIndex = 0;

        String[] lines = input.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            // Blank line
            if (trimmed.isEmpty()) {
                i++;
                continue;
            }

            // Comment
            if (trimmed.startsWith("#")) {
                comments.add(trimmed.substring(1).trim());
                i++;
                continue;
            }

            // Section separator
            if (trimmed.equals("---")) {
                sections.add(currentSection);
                sectionIndex++;
                currentSection = new Section(sectionIndex, new ArrayList<>(), new LinkedHashMap<>());
                i++;
                continue;
            }

            // Declaration: :name::: content :::
            if (trimmed.startsWith(":") && !trimmed.startsWith(":::")) {
                ParsedDeclaration decl = parseDeclaration(lines, i);
                if (decl != null) {
                    currentSection.declarations().put(decl.name, decl.content);
                    i = decl.endLine + 1;
                    continue;
                }
            }

            // Operation: command arg arg ...
            if (Character.isLetter(trimmed.charAt(0))) {
                Operation op = parseOperation(line, i + 1, currentSection.declarations());
                if (op != null) {
                    currentSection.operations().add(op);
                }
                i++;
                continue;
            }

            // Skip unrecognized lines
            i++;
        }

        sections.add(currentSection);
        return new Plan(sections, comments);
    }

    private static Operation parseOperation(String line, int lineNumber,
                                            Map<String, String> declarations) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || !Character.isLetter(trimmed.charAt(0))) return null;

        List<String> tokens = tokenize(trimmed);
        if (tokens.isEmpty()) return null;

        String command = tokens.get(0);
        List<Argument> arguments = new ArrayList<>();

        int idx = 1;
        while (idx < tokens.size()) {
            String tok = tokens.get(idx);

            if (isDelimiter(tok)) {
                // Inline content block
                StringBuilder content = new StringBuilder();
                idx++;
                while (idx < tokens.size() && !isDelimiter(tokens.get(idx))) {
                    if (!content.isEmpty()) content.append(" ");
                    content.append(tokens.get(idx));
                    idx++;
                }
                arguments.add(new Argument.ContentBlock(content.toString().trim(), tok));
                if (idx < tokens.size()) idx++; // skip closing delimiter
            } else if (tok.startsWith(":") && tok.length() > 1 && !tok.startsWith(":::")) {
                // Declaration reference
                arguments.add(new Argument.DeclRef(tok.substring(1)));
                idx++;
            } else {
                // Token
                arguments.add(new Argument.Token(tok));
                idx++;
            }
        }

        return new Operation(command, arguments, lineNumber);
    }

    private record ParsedDeclaration(String name, String content, int endLine) {}

    private static ParsedDeclaration parseDeclaration(String[] lines, int startLine) {
        String line = lines[startLine].trim();

        // Extract :name part
        int delimStart = line.indexOf(":::");
        if (delimStart < 0) {
            // Check other delimiters
            for (String d : List.of("###", "$$$", "@@@", "%%%")) {
                delimStart = line.indexOf(d);
                if (delimStart >= 0) break;
            }
        }
        if (delimStart <= 1) return null; // need at least :<char>

        String name = line.substring(1, delimStart);
        if (name.isEmpty() || !Character.isLetter(name.charAt(0))) return null;

        String delimiter = line.substring(delimStart, delimStart + 3);
        String rest = line.substring(delimStart + 3);

        // Single-line: :name::: content :::
        int closeIdx = rest.indexOf(delimiter);
        if (closeIdx >= 0) {
            String content = rest.substring(0, closeIdx).trim();
            return new ParsedDeclaration(name, content, startLine);
        }

        // Multi-line: :name:::\ncontent\n:::
        StringBuilder content = new StringBuilder();
        if (!rest.trim().isEmpty()) content.append(rest);
        int i = startLine + 1;
        while (i < lines.length) {
            if (lines[i].trim().equals(delimiter)) {
                return new ParsedDeclaration(name, content.toString().trim(), i);
            }
            if (!content.isEmpty()) content.append("\n");
            content.append(lines[i]);
            i++;
        }

        // Unclosed — treat as single-line
        return new ParsedDeclaration(name, rest.trim(), startLine);
    }

    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < line.length()) {
            // Skip whitespace
            while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
            if (i >= line.length()) break;

            // Check for 3-char delimiter
            if (i + 2 < line.length()) {
                String three = line.substring(i, i + 3);
                if (isDelimiter(three)) {
                    tokens.add(three);
                    i += 3;
                    continue;
                }
            }

            // Read until next whitespace or embedded delimiter
            StringBuilder sb = new StringBuilder();
            while (i < line.length() && line.charAt(i) != ' ' && line.charAt(i) != '\t') {
                // Check for embedded 3-char delimiter
                if (i + 2 < line.length()) {
                    String three = line.substring(i, i + 3);
                    if (isDelimiter(three)) {
                        if (!sb.isEmpty()) {
                            tokens.add(sb.toString());
                            sb.setLength(0);
                        }
                        tokens.add(three);
                        i += 3;
                        continue;
                    }
                }
                sb.append(line.charAt(i));
                i++;
            }
            if (!sb.isEmpty()) tokens.add(sb.toString());
        }
        return tokens;
    }

    private static boolean isDelimiter(String s) {
        return ":::".equals(s) || "###".equals(s) || "$$$".equals(s) ||
               "@@@".equals(s) || "%%%".equals(s);
    }
}
