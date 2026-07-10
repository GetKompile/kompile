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

package ai.kompile.loader.excel.graph;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import static ai.kompile.core.graphrag.GraphConstants.ENTITY_TABLE;
import static ai.kompile.core.graphrag.GraphConstants.PROP_ENTITY_SOURCE;
import static ai.kompile.core.graphrag.GraphConstants.PROVENANCE_EXTRACTED;
import static ai.kompile.core.graphrag.GraphConstants.REL_CONTAINS;
import static ai.kompile.core.graphrag.GraphConstants.SOURCE_EXCEL_LOADER;

/**
 * Projects plain worksheet grids into first-class table entities with typed, keyed members.
 *
 * <p>Most business workbooks never use Excel ListObjects, yet their sheets carry real tables: a
 * header row followed by contiguous data rows. Each such block becomes an inferred
 * {@code TABLE} entity. When a block has a unique text key column, it is a master/dimension
 * table, and its rows are additionally projected as member entities whose TYPE is named by the
 * key column header ("Corp SKU" &rarr; {@code SKU}, "Corp channel" &rarr; {@code CHANNEL},
 * "Region" &rarr; {@code REGION}). The member's key and display name become aliases, which gives
 * downstream reasoning code-name identity ("RST-001" is "Restful Bath Salt 16oz") learned
 * entirely from the source document.</p>
 *
 * <p>Grand-total rows (keys starting with "total") are excluded: they are aggregate markers, not
 * members. No domain vocabulary is built in.</p>
 */
final class InferredTableProjector {

    private static final int MAX_TABLES_PER_SHEET = 8;
    private static final int MAX_MEMBERS_PER_TABLE = 200;
    private static final int MAX_KEY_LENGTH = 40;
    private static final int MAX_ATTRIBUTE_LENGTH = 120;
    private static final Set<String> KEY_HEADER_NOISE = Set.of(
            "corp", "corporate", "code", "codes", "id", "ids", "key", "keys",
            "no", "num", "number");
    private static final Set<String> NAME_HEADER_TOKENS = Set.of(
            "name", "names", "label", "labels", "title", "titles");

    private InferredTableProjector() {
    }

    static void project(
            String ns,
            String workbookName,
            Map<String, List<CellNode>> cellsBySheet,
            Map<String, NavigableMap<Integer, String>> loneTextRowsBySheet,
            Map<String, Entity> sheetEntities,
            Graph graph) {
        List<MemberRecord> members = new ArrayList<>();
        for (Map.Entry<String, List<CellNode>> sheet : cellsBySheet.entrySet()) {
            projectSheet(ns, workbookName, sheet.getKey(), sheet.getValue(),
                    loneTextRowsBySheet.getOrDefault(sheet.getKey(), new TreeMap<>()),
                    sheetEntities.get(sheet.getKey()), graph, members);
        }
        linkMemberReferences(members, graph);
    }

    /** A projected member row retained for the workbook-level reference-linking pass. */
    private record MemberRecord(
            String entityId,
            String memberType,
            String key,
            String label,
            Map<String, String> columnValues) {
    }

    /**
     * Columns whose values resolve to another master's members become typed reference relations:
     * a SKU master's "Brand" column pointing at Brand members emits
     * {@code SKU --HAS_BRAND--> BRAND} edges. A column qualifies only when at least two distinct
     * rows match and at least 80% of its non-blank values resolve, so coincidental overlaps do
     * not become schema.
     */
    private static void linkMemberReferences(List<MemberRecord> members, Graph graph) {
        Map<String, Map<String, MemberRecord>> indexByType = new LinkedHashMap<>();
        for (MemberRecord member : members) {
            Map<String, MemberRecord> byValue = indexByType
                    .computeIfAbsent(member.memberType(), ignored -> new LinkedHashMap<>());
            byValue.putIfAbsent(normalize(member.key()), member);
            byValue.putIfAbsent(normalize(member.label()), member);
        }

        Map<String, List<MemberRecord>> byType = new LinkedHashMap<>();
        for (MemberRecord member : members) {
            byType.computeIfAbsent(member.memberType(), ignored -> new ArrayList<>()).add(member);
        }
        for (Map.Entry<String, List<MemberRecord>> sourceType : byType.entrySet()) {
            Set<String> columns = new LinkedHashSet<>();
            sourceType.getValue().forEach(member -> columns.addAll(member.columnValues().keySet()));
            for (String column : columns) {
                List<MemberRecord> sources = new ArrayList<>();
                List<String> values = new ArrayList<>();
                for (MemberRecord member : sourceType.getValue()) {
                    String value = member.columnValues().get(column);
                    if (value != null && !value.isBlank()) {
                        sources.add(member);
                        values.add(value);
                    }
                }
                if (values.size() < 2) {
                    continue;
                }
                for (Map.Entry<String, Map<String, MemberRecord>> targetType
                        : indexByType.entrySet()) {
                    if (targetType.getKey().equals(sourceType.getKey())) {
                        continue;
                    }
                    List<Integer> matched = new ArrayList<>();
                    Set<String> distinctTargets = new LinkedHashSet<>();
                    for (int i = 0; i < values.size(); i++) {
                        MemberRecord target = targetType.getValue().get(normalize(values.get(i)));
                        if (target != null) {
                            matched.add(i);
                            distinctTargets.add(target.entityId());
                        }
                    }
                    if (distinctTargets.size() < 2
                            || matched.size() * 5 < values.size() * 4) {
                        continue;
                    }
                    String relationType = "HAS_" + normalize(column)
                            .replace(' ', '_').toUpperCase(Locale.ROOT);
                    for (int index : matched) {
                        MemberRecord source = sources.get(index);
                        MemberRecord target = targetType.getValue()
                                .get(normalize(values.get(index)));
                        Relationship reference = new Relationship();
                        reference.setSource(source.entityId());
                        reference.setTarget(target.entityId());
                        reference.setType(relationType);
                        reference.setDescription(String.format("%s '%s' %s %s '%s'",
                                source.memberType(), source.label(), relationType.toLowerCase(Locale.ROOT),
                                target.memberType(), target.label()));
                        reference.setWeight(0.9);
                        reference.setConfidence(1.0);
                        reference.setMetadata(Map.of(
                                "provenance", PROVENANCE_EXTRACTED,
                                "memberReference", true,
                                "column", column));
                        graph.getRelationships().add(reference);
                    }
                    break;
                }
            }
        }
    }

    private static void projectSheet(
            String ns,
            String workbookName,
            String sheetName,
            List<CellNode> cells,
            NavigableMap<Integer, String> loneTextRows,
            Entity sheetEntity,
            Graph graph,
            List<MemberRecord> members) {
        TreeMap<Integer, Map<Integer, CellNode>> byRow = new TreeMap<>();
        for (CellNode cell : cells) {
            byRow.computeIfAbsent(cell.getRow(), ignored -> new TreeMap<>())
                    .put(columnIndex(cell.getColumn()), cell);
        }
        int firstOccupiedRow = byRow.isEmpty() ? Integer.MAX_VALUE : byRow.firstKey();

        int tables = 0;
        Integer row = byRow.isEmpty() ? null : byRow.firstKey();
        while (row != null && tables < MAX_TABLES_PER_SHEET) {
            Map<Integer, CellNode> headerCandidate = byRow.get(row);
            Map<Integer, String> headers = headerRow(headerCandidate, loneTextRows, row);
            if (headers == null) {
                row = byRow.higherKey(row);
                continue;
            }

            List<Map<Integer, CellNode>> dataRows = new ArrayList<>();
            List<Integer> dataRowIndexes = new ArrayList<>();
            int expected = row + 1;
            while (byRow.containsKey(expected) && !loneTextRows.containsKey(expected)) {
                Map<Integer, CellNode> candidate = byRow.get(expected);
                boolean underHeader = candidate.keySet().stream().anyMatch(headers::containsKey);
                if (!underHeader) {
                    break;
                }
                dataRows.add(candidate);
                dataRowIndexes.add(expected);
                expected++;
            }
            if (dataRows.size() < 2) {
                row = byRow.higherKey(row);
                continue;
            }

            tables++;
            emitTable(ns, workbookName, sheetName, row, headers, dataRows, dataRowIndexes,
                    loneTextRows, firstOccupiedRow, sheetEntity, graph, members);
            row = byRow.higherKey(expected - 1);
        }
    }

    /** A header row has at least two text labels and nothing else. */
    private static Map<Integer, String> headerRow(
            Map<Integer, CellNode> cells, NavigableMap<Integer, String> loneTextRows, int row) {
        if (cells == null || cells.size() < 2 || loneTextRows.containsKey(row)) {
            return null;
        }
        Map<Integer, String> headers = new LinkedHashMap<>();
        for (Map.Entry<Integer, CellNode> cell : cells.entrySet()) {
            if (!isTextLabel(cell.getValue())) {
                return null;
            }
            headers.put(cell.getKey(), cell.getValue().getDisplayValue().trim());
        }
        return headers;
    }

    private static void emitTable(
            String ns,
            String workbookName,
            String sheetName,
            int headerRow,
            Map<Integer, String> headers,
            List<Map<Integer, CellNode>> dataRows,
            List<Integer> dataRowIndexes,
            NavigableMap<Integer, String> loneTextRows,
            int firstOccupiedRow,
            Entity sheetEntity,
            Graph graph,
            List<MemberRecord> memberRecords) {
        Map.Entry<Integer, String> section = loneTextRows.lowerEntry(headerRow);
        String sectionLabel = section == null || section.getKey().equals(firstOccupiedRow)
                ? null : section.getValue();
        String tableLabel = truncate(sectionLabel == null
                ? sheetName : sheetName + " - " + sectionLabel, 80);
        String tableId = ns + "table:" + sheetName + "!r" + headerRow;

        Integer keyColumn = keyColumn(headers, dataRows);
        String memberType = keyColumn == null
                ? null : memberType(headers.get(keyColumn));
        // A type must be nameable: purely numeric headers (numbered-list columns) define no type.
        if (memberType != null && !memberType.chars().anyMatch(Character::isLetter)) {
            memberType = null;
        }

        Entity tableEntity = new Entity();
        tableEntity.setId(tableId);
        tableEntity.setTitle(tableLabel);
        tableEntity.setType(ENTITY_TABLE);
        tableEntity.setDescription(String.format(
                "Inferred table '%s' (%d columns x %d rows, header row %d)",
                tableLabel, headers.size(), dataRows.size(), headerRow));
        tableEntity.setConfidence(1.0);
        Map<String, Object> tableMeta = new LinkedHashMap<>();
        tableMeta.put("inferred", true);
        tableMeta.put("isComposite", true);
        tableMeta.put("sheetName", sheetName);
        tableMeta.put("workbook", workbookName == null ? "unknown" : workbookName);
        tableMeta.put("headerRow", headerRow);
        tableMeta.put("rowCount", dataRows.size());
        tableMeta.put("columns", List.copyOf(headers.values()));
        if (memberType != null) {
            tableMeta.put("keyColumn", headers.get(keyColumn));
            tableMeta.put("memberType", memberType);
        }
        tableMeta.put(PROP_ENTITY_SOURCE, SOURCE_EXCEL_LOADER);
        tableEntity.setMetadata(tableMeta);
        graph.getEntities().add(tableEntity);

        if (sheetEntity != null) {
            Relationship contains = new Relationship();
            contains.setSource(sheetEntity.getId());
            contains.setTarget(tableId);
            contains.setType(REL_CONTAINS);
            contains.setDescription(String.format(
                    "Sheet '%s' contains inferred table '%s'", sheetName, tableLabel));
            contains.setWeight(0.9);
            contains.setConfidence(1.0);
            contains.setMetadata(Map.of("provenance", PROVENANCE_EXTRACTED, "inferred", true));
            graph.getRelationships().add(contains);
        }
        if (memberType == null) {
            return;
        }

        // Member rows exclude aggregate markers; the name column is judged on the same population.
        List<Map<Integer, CellNode>> memberRows = new ArrayList<>();
        List<Integer> memberRowIndexes = new ArrayList<>();
        for (int i = 0; i < dataRows.size(); i++) {
            CellNode keyCell = dataRows.get(i).get(keyColumn);
            if (keyCell == null || normalize(keyCell.getDisplayValue()).startsWith("total")) {
                continue;
            }
            memberRows.add(dataRows.get(i));
            memberRowIndexes.add(dataRowIndexes.get(i));
        }
        Integer nameColumn = nameColumn(headers, memberRows, keyColumn);
        int members = 0;
        for (int i = 0; i < memberRows.size() && members < MAX_MEMBERS_PER_TABLE; i++) {
            Map<Integer, CellNode> dataRow = memberRows.get(i);
            String key = dataRow.get(keyColumn).getDisplayValue().trim();
            String label = nameColumn == null ? key : displayValue(dataRow.get(nameColumn), key);
            members++;

            Entity member = new Entity();
            member.setId(ns + "member:" + memberType.toLowerCase(Locale.ROOT)
                    + ":" + normalize(key).replace(' ', '_'));
            member.setTitle(label);
            member.setType(memberType);
            member.setDescription(String.format(
                    "%s '%s' (key %s) defined by table '%s'", memberType, label, key, tableLabel));
            member.setConfidence(1.0);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("tableMember", true);
            meta.put("memberType", memberType);
            meta.put("memberKey", key);
            meta.put("memberTable", tableLabel);
            meta.put("sheetName", sheetName);
            meta.put("workbook", workbookName == null ? "unknown" : workbookName);
            meta.put("row", memberRowIndexes.get(i));
            Set<String> aliases = new LinkedHashSet<>();
            aliases.add(key);
            aliases.add(label);
            meta.put("aliases", List.copyOf(aliases));
            Map<String, String> columnValues = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> header : headers.entrySet()) {
                if (header.getKey().equals(keyColumn)) {
                    continue;
                }
                CellNode valueCell = dataRow.get(header.getKey());
                if (valueCell == null || valueCell.getDisplayValue() == null
                        || valueCell.getDisplayValue().isBlank()) {
                    continue;
                }
                String value = truncate(valueCell.getDisplayValue().trim(), MAX_ATTRIBUTE_LENGTH);
                columnValues.putIfAbsent(header.getValue(), value);
                String attributeKey = normalize(header.getValue()).replace(' ', '_');
                if (!attributeKey.isBlank()) {
                    meta.putIfAbsent(attributeKey, value);
                }
            }
            meta.put(PROP_ENTITY_SOURCE, SOURCE_EXCEL_LOADER);
            member.setMetadata(meta);
            graph.getEntities().add(member);

            Relationship definedBy = new Relationship();
            definedBy.setSource(tableId);
            definedBy.setTarget(member.getId());
            definedBy.setType(REL_CONTAINS);
            definedBy.setDescription(String.format(
                    "Table '%s' defines %s '%s'", tableLabel, memberType, label));
            definedBy.setWeight(0.9);
            definedBy.setConfidence(1.0);
            definedBy.setMetadata(Map.of("provenance", PROVENANCE_EXTRACTED, "inferred", true));
            graph.getRelationships().add(definedBy);

            memberRecords.add(new MemberRecord(
                    member.getId(), memberType, key, label, columnValues));
        }
    }

    /**
     * Leftmost column whose value in every data row is unique, short, constant text — the natural
     * key that makes the block a master/dimension table.
     */
    private static Integer keyColumn(
            Map<Integer, String> headers, List<Map<Integer, CellNode>> dataRows) {
        for (Integer column : headers.keySet()) {
            Set<String> seen = new LinkedHashSet<>();
            boolean qualified = true;
            for (Map<Integer, CellNode> dataRow : dataRows) {
                CellNode cell = dataRow.get(column);
                if (cell == null || !isTextLabel(cell)
                        || cell.getDisplayValue().trim().length() > MAX_KEY_LENGTH
                        || !seen.add(normalize(cell.getDisplayValue()))) {
                    qualified = false;
                    break;
                }
            }
            if (qualified) {
                return column;
            }
        }
        return null;
    }

    /**
     * The display-name column: header names a name/label/title and values are plain (no delimited
     * lists, which are alias rows handled elsewhere).
     */
    private static Integer nameColumn(
            Map<Integer, String> headers,
            List<Map<Integer, CellNode>> dataRows,
            Integer keyColumn) {
        for (Map.Entry<Integer, String> header : headers.entrySet()) {
            if (header.getKey().equals(keyColumn)) {
                continue;
            }
            boolean nameish = false;
            for (String token : normalize(header.getValue()).split(" ")) {
                if (NAME_HEADER_TOKENS.contains(token)) {
                    nameish = true;
                    break;
                }
            }
            if (!nameish) {
                continue;
            }
            boolean plain = true;
            for (Map<Integer, CellNode> dataRow : dataRows) {
                CellNode cell = dataRow.get(header.getKey());
                String value = cell == null ? null : cell.getDisplayValue();
                if (value != null && (value.contains(",") || value.contains(";"))) {
                    plain = false;
                    break;
                }
            }
            if (plain) {
                return header.getKey();
            }
        }
        return null;
    }

    /** "Corp SKU" -> SKU, "Corp channel" -> CHANNEL, "Region" -> REGION. */
    private static String memberType(String keyHeader) {
        List<String> kept = new ArrayList<>();
        for (String token : normalize(keyHeader).split(" ")) {
            if (!token.isBlank() && !KEY_HEADER_NOISE.contains(token)) {
                kept.add(token);
            }
        }
        String type = String.join("_", kept);
        if (type.isBlank()) {
            type = normalize(keyHeader).replace(' ', '_');
        }
        return type.toUpperCase(Locale.ROOT);
    }

    private static String displayValue(CellNode cell, String fallback) {
        return cell == null || cell.getDisplayValue() == null
                || cell.getDisplayValue().isBlank() ? fallback : cell.getDisplayValue().trim();
    }

    private static boolean isTextLabel(CellNode cell) {
        return cell != null && "STRING".equals(cell.getCellType())
                && cell.getDisplayValue() != null && !cell.getDisplayValue().isBlank();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }

    private static int columnIndex(String column) {
        if (column == null || column.isBlank()) {
            return 0;
        }
        int result = 0;
        for (int i = 0; i < column.length(); i++) {
            char c = Character.toUpperCase(column.charAt(i));
            if (c < 'A' || c > 'Z') {
                break;
            }
            result = result * 26 + (c - 'A' + 1);
        }
        return result - 1;
    }
}
