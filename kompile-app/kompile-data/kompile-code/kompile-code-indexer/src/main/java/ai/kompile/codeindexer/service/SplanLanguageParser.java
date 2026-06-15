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

package ai.kompile.codeindexer.service;

import ai.kompile.codeindexer.domain.CodeEntity;
import ai.kompile.codeindexer.domain.CodeEntityType;
import ai.kompile.codeindexer.domain.CodeRelationType;
import ai.kompile.codeindexer.service.CodeEntityExtractor.RelationTriple;
import ai.kompile.codeindexer.splan.SplanPlanParser;
import ai.kompile.codeindexer.splan.SplanPlanParser.Argument;
import ai.kompile.codeindexer.splan.SplanPlanParser.Operation;
import ai.kompile.codeindexer.splan.SplanPlanParser.Plan;
import ai.kompile.codeindexer.splan.SplanPlanParser.Section;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Language parser for {@code .splan} files. Uses the hand-written
 * {@link SplanPlanParser} (recursive-descent) so that the ANTLR runtime
 * is not required on the classpath at index time.
 *
 * <h3>Entity mapping</h3>
 * <ul>
 *   <li><b>Plan file</b> → {@code FILE} — one per file, created by the caller
 *       ({@link CodeEntityExtractor}); this parser does <em>not</em> emit an
 *       additional FILE entity.</li>
 *   <li><b>Section</b> → {@code MODULE} — one per section, named
 *       {@code "section-0"}, {@code "section-1"}, …</li>
 *   <li><b>Declaration</b> → {@code CONSTANT} — named after the declaration
 *       key; content stored in {@code contentPreview} (first 200 chars).</li>
 *   <li><b>Operation</b> → {@code FUNCTION} — command as name; argument
 *       types encoded in {@code signature}; line number from the parser.</li>
 *   <li><b>ContentBlock argument</b> → {@code FIELD} — content preview +
 *       delimiter type stored in {@code metadataJson}.</li>
 * </ul>
 *
 * <h3>Relation mapping</h3>
 * <ul>
 *   <li>FILE → CONTAINS → each Section MODULE</li>
 *   <li>Section MODULE → CONTAINS → each Operation FUNCTION</li>
 *   <li>Section MODULE → CONTAINS → each Declaration CONSTANT</li>
 *   <li>Operation FUNCTION → DEPENDS_ON → Declaration CONSTANT
 *       (when an argument is a {@link Argument.DeclRef})</li>
 * </ul>
 */
@Component
public class SplanLanguageParser implements LanguageParser {

    private static final int CONTENT_PREVIEW_MAX = 200;

    @Override
    public Set<String> supportedLanguages() {
        return Set.of("splan");
    }

    @Override
    public ExtractionOutput parse(String[] lines, String filePath, String projectId, String language) {
        List<CodeEntity> entities = new ArrayList<>();
        List<RelationTriple> relations = new ArrayList<>();

        String fullText = String.join("\n", lines);
        Plan plan = SplanPlanParser.parse(fullText);

        // Track the running source-line cursor so we can assign approximate
        // startLine values to sections and declarations (the parser only
        // records line numbers for individual Operations).
        int runningLine = 1;

        for (Section section : plan.sections()) {
            String sectionName = "section-" + section.index();
            String sectionFqn  = filePath + ":" + sectionName;

            // Section start: advance past blank lines / comments until we
            // find a non-trivial line or an operation's lineNumber gives us
            // a lower bound.
            int sectionStartLine = estimateSectionStart(section, runningLine);

            CodeEntity sectionEntity = CodeEntity.builder()
                    .projectId(projectId)
                    .entityType(CodeEntityType.MODULE)
                    .name(sectionName)
                    .fullyQualifiedName(sectionFqn)
                    .filePath(filePath)
                    .language(language)
                    .startLine(sectionStartLine)
                    .parentFqn(filePath)
                    .build();
            entities.add(sectionEntity);

            // FILE → CONTAINS → Section MODULE
            relations.add(new RelationTriple(filePath, sectionFqn, CodeRelationType.CONTAINS));

            // -----------------------------------------------------------------
            // Declarations  →  CONSTANT entities
            // -----------------------------------------------------------------
            int declLine = sectionStartLine;
            for (var entry : section.declarations().entrySet()) {
                String declName    = entry.getKey();
                String declContent = entry.getValue();
                String declFqn     = filePath + ":" + declName;

                String preview = declContent != null && declContent.length() > CONTENT_PREVIEW_MAX
                        ? declContent.substring(0, CONTENT_PREVIEW_MAX)
                        : declContent;

                CodeEntity declEntity = CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.CONSTANT)
                        .name(declName)
                        .fullyQualifiedName(declFqn)
                        .filePath(filePath)
                        .language(language)
                        .startLine(declLine)
                        .contentPreview(preview)
                        .parentFqn(sectionFqn)
                        .build();
                entities.add(declEntity);

                // Section MODULE → CONTAINS → Declaration CONSTANT
                relations.add(new RelationTriple(sectionFqn, declFqn, CodeRelationType.CONTAINS));

                declLine++;
            }

            // -----------------------------------------------------------------
            // Operations  →  FUNCTION entities (+ inline ContentBlock FIELDs)
            // -----------------------------------------------------------------
            for (Operation op : section.operations()) {
                String opName = op.command();
                String opFqn  = filePath + ":" + sectionName + ":" + opName + "@" + op.lineNumber();

                String signature = buildSignature(op);

                CodeEntity opEntity = CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.FUNCTION)
                        .name(opName)
                        .fullyQualifiedName(opFqn)
                        .filePath(filePath)
                        .language(language)
                        .startLine(op.lineNumber())
                        .endLine(op.lineNumber())
                        .signature(signature)
                        .parentFqn(sectionFqn)
                        .build();
                entities.add(opEntity);

                // Section MODULE → CONTAINS → Operation FUNCTION
                relations.add(new RelationTriple(sectionFqn, opFqn, CodeRelationType.CONTAINS));

                // Process individual arguments
                int argIndex = 0;
                for (Argument arg : op.arguments()) {
                    if (arg instanceof Argument.DeclRef ref) {
                        // Operation FUNCTION → DEPENDS_ON → Declaration CONSTANT
                        String declFqn = filePath + ":" + ref.name();
                        relations.add(new RelationTriple(opFqn, declFqn, CodeRelationType.DEPENDS_ON));
                    } else if (arg instanceof Argument.ContentBlock block) {
                        // ContentBlock  →  FIELD entity
                        String blockName = opName + "-block-" + argIndex;
                        String blockFqn  = opFqn + ":" + blockName;
                        String preview = block.content().length() > CONTENT_PREVIEW_MAX
                                ? block.content().substring(0, CONTENT_PREVIEW_MAX)
                                : block.content();

                        CodeEntity blockEntity = CodeEntity.builder()
                                .projectId(projectId)
                                .entityType(CodeEntityType.FIELD)
                                .name(blockName)
                                .fullyQualifiedName(blockFqn)
                                .filePath(filePath)
                                .language(language)
                                .startLine(op.lineNumber())
                                .endLine(op.lineNumber())
                                .contentPreview(preview)
                                .metadataJson("{\"delimiter\":\"" + block.delimiter() + "\"}")
                                .parentFqn(opFqn)
                                .build();
                        entities.add(blockEntity);

                        // Operation FUNCTION → CONTAINS → ContentBlock FIELD
                        relations.add(new RelationTriple(opFqn, blockFqn, CodeRelationType.CONTAINS));
                    }
                    // Token arguments carry no extra entity
                    argIndex++;
                }

                // Advance running-line cursor to the highest operation line seen
                runningLine = Math.max(runningLine, op.lineNumber());
            }

            // Move cursor past this section's separator ("---" counts as 1 line)
            runningLine++;
        }

        return new ExtractionOutput(entities, relations);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Build a human-readable signature for an operation, e.g.:
     * {@code "write (token token declRef contentBlock)"}
     */
    private String buildSignature(Operation op) {
        if (op.arguments().isEmpty()) {
            return op.command() + "()";
        }
        String argTypes = op.arguments().stream()
                .map(arg -> {
                    if (arg instanceof Argument.Token) return "token";
                    else if (arg instanceof Argument.DeclRef r) return "declRef:" + r.name();
                    else if (arg instanceof Argument.ContentBlock b) return "contentBlock[" + b.delimiter() + "]";
                    else return "arg";
                })
                .collect(Collectors.joining(" "));
        return op.command() + " (" + argTypes + ")";
    }

    /**
     * Estimate the 1-based start line of a section.
     * Uses the minimum operation lineNumber as a lower bound; otherwise falls
     * back to the running cursor supplied by the caller.
     */
    private int estimateSectionStart(Section section, int runningLine) {
        OptionalInt minLine = section.operations().stream()
                .mapToInt(Operation::lineNumber)
                .min();
        if (minLine.isPresent()) {
            return Math.max(1, minLine.getAsInt() - section.declarations().size());
        }
        return runningLine;
    }
}
