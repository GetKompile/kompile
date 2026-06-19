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

package ai.kompile.crawler.sql;

import java.util.Map;

/**
 * Metadata for a single row discovered from a SQL query or table scan.
 *
 * @param tableName   The source table name (or "query" for custom queries)
 * @param rowId       The primary key value(s) as a string, or the row index if no PK
 * @param rowIndex    Zero-based index within the result set
 * @param columnNames Ordered list of column names
 * @param columns     Column name to value map for this row
 */
public record SqlRowEntry(
        String tableName,
        String rowId,
        long rowIndex,
        String[] columnNames,
        Map<String, Object> columns
) {
}
