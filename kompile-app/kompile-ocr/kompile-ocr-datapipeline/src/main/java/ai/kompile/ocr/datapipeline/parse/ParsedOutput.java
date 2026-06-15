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

package ai.kompile.ocr.datapipeline.parse;

import ai.kompile.ocr.datapipeline.config.OutputParseConfig;
import ai.kompile.ocr.datapipeline.entity.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Result of parsing model output into structured entities.
 *
 * @param rawOutput The original model output
 * @param format The format of the raw output
 * @param entities List of extracted entities
 * @param fullText Plain text content (if available)
 */
public record ParsedOutput(
        String rawOutput,
        OutputParseConfig.OutputFormat format,
        List<DocumentEntity> entities,
        String fullText
) {
    /**
     * Creates an empty parsed output.
     */
    public static ParsedOutput empty(OutputParseConfig.OutputFormat format) {
        return new ParsedOutput(null, format, new ArrayList<>(), null);
    }

    /**
     * Creates a parsed output with entities.
     */
    public static ParsedOutput withEntities(String raw, OutputParseConfig.OutputFormat format,
                                             List<DocumentEntity> entities) {
        return new ParsedOutput(raw, format, entities, null);
    }

    /**
     * Gets all table entities.
     */
    public List<TableEntity> getTables() {
        return entities.stream()
                .filter(e -> e instanceof TableEntity)
                .map(e -> (TableEntity) e)
                .collect(Collectors.toList());
    }

    /**
     * Gets all figure entities.
     */
    public List<FigureEntity> getFigures() {
        return entities.stream()
                .filter(e -> e instanceof FigureEntity)
                .map(e -> (FigureEntity) e)
                .collect(Collectors.toList());
    }

    /**
     * Gets all formula entities.
     */
    public List<FormulaEntity> getFormulas() {
        return entities.stream()
                .filter(e -> e instanceof FormulaEntity)
                .map(e -> (FormulaEntity) e)
                .collect(Collectors.toList());
    }

    /**
     * Gets all code entities.
     */
    public List<CodeEntity> getCodeBlocks() {
        return entities.stream()
                .filter(e -> e instanceof CodeEntity)
                .map(e -> (CodeEntity) e)
                .collect(Collectors.toList());
    }

    /**
     * Gets entities by type.
     */
    @SuppressWarnings("unchecked")
    public <T extends DocumentEntity> List<T> getEntitiesByType(Class<T> type) {
        return entities.stream()
                .filter(type::isInstance)
                .map(e -> (T) e)
                .collect(Collectors.toList());
    }

    /**
     * Checks if any entities were extracted.
     */
    public boolean hasEntities() {
        return entities != null && !entities.isEmpty();
    }

    /**
     * Gets the total entity count.
     */
    public int getEntityCount() {
        return entities != null ? entities.size() : 0;
    }
}
