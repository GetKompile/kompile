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

package ai.kompile.codeindexer.service.parsers;

import ai.kompile.codeindexer.domain.CodeEntity;
import ai.kompile.codeindexer.domain.CodeEntityType;
import ai.kompile.codeindexer.domain.CodeRelationType;
import ai.kompile.codeindexer.service.CodeEntityExtractor.RelationTriple;
import ai.kompile.codeindexer.service.LanguageParser;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Language parser for config, infrastructure, build, and functional languages.
 *
 * Supported languages:
 *   sql, bash, powershell, make, dockerfile, terraform, hcl, protobuf, graphql,
 *   haskell, ocaml, elixir, erlang, r, julia, clojure
 *
 * Each language uses targeted regex patterns to extract the structural entities
 * most relevant to code navigation and search — table/function definitions,
 * module declarations, type aliases, imports, etc.
 */
@Component
public class ConfigLanguageParser implements LanguageParser {

    private static final Set<String> SUPPORTED = Set.of(
            "sql", "bash", "powershell", "make", "dockerfile",
            "terraform", "hcl", "protobuf", "graphql",
            "haskell", "ocaml", "elixir", "erlang",
            "r", "julia", "clojure"
    );

    // -------------------------------------------------------------------------
    // SQL
    // -------------------------------------------------------------------------
    private static final Pattern SQL_CREATE_TABLE = Pattern.compile(
            "(?i)^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:TEMP(?:ORARY)?\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?" +
            "(?:[\\w.`\"\\[\\]]+\\.)?([\\w`\"\\[\\]]+)\\s*[\\(;]?");
    private static final Pattern SQL_CREATE_VIEW = Pattern.compile(
            "(?i)^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:TEMP(?:ORARY)?\\s+)?(?:MATERIALIZED\\s+)?VIEW\\s+" +
            "(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:[\\w.`\"\\[\\]]+\\.)?([\\w`\"\\[\\]]+)\\s");
    private static final Pattern SQL_CREATE_INDEX = Pattern.compile(
            "(?i)^\\s*CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:CONCURRENTLY\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w`\"\\[\\]]+)\\s+ON\\s+" +
            "(?:[\\w.`\"\\[\\]]+\\.)?([\\w`\"\\[\\]]+)");
    private static final Pattern SQL_CREATE_FUNCTION = Pattern.compile(
            "(?i)^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?FUNCTION\\s+(?:[\\w.`\"]+\\.)?([\\w`\"]+)\\s*\\(");
    private static final Pattern SQL_CREATE_PROCEDURE = Pattern.compile(
            "(?i)^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?PROCEDURE\\s+(?:[\\w.`\"]+\\.)?([\\w`\"]+)\\s*\\(");
    private static final Pattern SQL_ALTER_TABLE = Pattern.compile(
            "(?i)^\\s*ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:[\\w.`\"\\[\\]]+\\.)?([\\w`\"\\[\\]]+)\\s");
    private static final Pattern SQL_FROM = Pattern.compile(
            "(?i)\\bFROM\\s+(?:[\\w.`\"\\[\\]]+\\.)?([\\w`\"\\[\\]]+)(?:\\s|,|$)");
    private static final Pattern SQL_JOIN = Pattern.compile(
            "(?i)\\bJOIN\\s+(?:[\\w.`\"\\[\\]]+\\.)?([\\w`\"\\[\\]]+)(?:\\s|$)");
    private static final Pattern SQL_CREATE_TRIGGER = Pattern.compile(
            "(?i)^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?TRIGGER\\s+(?:[\\w.`\"]+\\.)?([\\w`\"]+)\\s+" +
            "(?:BEFORE|AFTER|INSTEAD\\s+OF)\\s+\\w+.*?\\bON\\s+(?:[\\w.`\"\\[\\]]+\\.)?([\\w`\"\\[\\]]+)");
    private static final Pattern SQL_FOREIGN_KEY = Pattern.compile(
            "(?i)\\bFOREIGN\\s+KEY\\s*\\([^)]+\\)\\s*REFERENCES\\s+(?:[\\w.`\"\\[\\]]+\\.)?([\\w`\"\\[\\]]+)");
    private static final Pattern SQL_REFERENCES_INLINE = Pattern.compile(
            "(?i)\\bREFERENCES\\s+(?:[\\w.`\"\\[\\]]+\\.)?([\\w`\"\\[\\]]+)\\s*\\(");
    private static final Pattern SQL_COLUMN_DEF = Pattern.compile(
            "^\\s*([\\w`\"\\[\\]]+)\\s+(BIGINT|INT(?:EGER)?|SMALLINT|TINYINT|NUMERIC|DECIMAL|FLOAT|DOUBLE|REAL|" +
            "BOOLEAN|BOOL|CHAR|VARCHAR|NVARCHAR|TEXT|NTEXT|CLOB|BLOB|BINARY|VARBINARY|DATE|TIME(?:STAMP)?|" +
            "DATETIME|UUID|SERIAL|BIGSERIAL|JSON|JSONB|XML|MONEY|BYTEA|BIT|INTERVAL|ARRAY|INET|CIDR|MACADDR)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_INSERT_INTO = Pattern.compile(
            "(?i)^\\s*INSERT\\s+INTO\\s+(?:[\\w.`\"\\[\\]]+\\.)?([\\w`\"\\[\\]]+)");
    private static final Pattern SQL_UPDATE = Pattern.compile(
            "(?i)^\\s*UPDATE\\s+(?:[\\w.`\"\\[\\]]+\\.)?([\\w`\"\\[\\]]+)\\s+SET\\b");
    private static final Pattern SQL_CTE = Pattern.compile(
            "(?i)\\b(\\w+)\\s+AS\\s*\\(");

    // -------------------------------------------------------------------------
    // Bash
    // -------------------------------------------------------------------------
    private static final Pattern BASH_FUNCTION_KW = Pattern.compile(
            "^\\s*function\\s+(\\w+)\\s*(?:\\(\\s*\\))?\\s*\\{?");
    private static final Pattern BASH_FUNCTION_PLAIN = Pattern.compile(
            "^\\s*(\\w+)\\s*\\(\\s*\\)\\s*\\{");
    private static final Pattern BASH_SOURCE = Pattern.compile(
            "^\\s*(?:source|\\.(?=\\s))\\s+([^\\s#;]+)");
    private static final Pattern BASH_ALIAS = Pattern.compile(
            "^\\s*alias\\s+(\\w+)\\s*=");
    private static final Pattern BASH_EXPORT = Pattern.compile(
            "^\\s*export\\s+(?:-[a-zA-Z]+\\s+)?(\\w+)(?:=|\\s|$)");

    // -------------------------------------------------------------------------
    // PowerShell
    // -------------------------------------------------------------------------
    private static final Pattern PS_FUNCTION = Pattern.compile(
            "(?i)^\\s*function\\s+([\\w-]+)\\s*(?:\\{|\\()");
    private static final Pattern PS_IMPORT_MODULE = Pattern.compile(
            "(?i)^\\s*Import-Module\\s+([^\\s;#]+)");

    // -------------------------------------------------------------------------
    // Make
    // -------------------------------------------------------------------------
    private static final Pattern MAKE_TARGET = Pattern.compile(
            "^([\\w][\\w./%\\-]*)\\s*:(?:[^=]|$)");
    private static final Pattern MAKE_VARIABLE = Pattern.compile(
            "^([A-Z_][A-Z0-9_]*)\\s*(?::=|\\?=|!=|=)");

    // -------------------------------------------------------------------------
    // Dockerfile
    // -------------------------------------------------------------------------
    private static final Pattern DOCKER_FROM = Pattern.compile(
            "(?i)^\\s*FROM\\s+([^\\s]+)(?:\\s+AS\\s+(\\w+))?");
    private static final Pattern DOCKER_ARG = Pattern.compile(
            "(?i)^\\s*ARG\\s+(\\w+)(?:=.*)?");
    private static final Pattern DOCKER_LABEL = Pattern.compile(
            "(?i)^\\s*LABEL\\s+(.+)");
    private static final Pattern DOCKER_EXPOSE = Pattern.compile(
            "(?i)^\\s*EXPOSE\\s+(\\d+(?:/\\w+)?)");
    private static final Pattern DOCKER_COPY = Pattern.compile(
            "(?i)^\\s*COPY\\s+(.+)");
    private static final Pattern DOCKER_RUN = Pattern.compile(
            "(?i)^\\s*RUN\\s+(.{0,120})");
    private static final Pattern DOCKER_ENTRYPOINT = Pattern.compile(
            "(?i)^\\s*ENTRYPOINT\\s+(.+)");
    private static final Pattern DOCKER_CMD = Pattern.compile(
            "(?i)^\\s*CMD\\s+(.+)");

    // -------------------------------------------------------------------------
    // Terraform / HCL
    // -------------------------------------------------------------------------
    private static final Pattern TF_RESOURCE = Pattern.compile(
            "^\\s*resource\\s+\"([^\"]+)\"\\s+\"([^\"]+)\"\\s*\\{");
    private static final Pattern TF_DATA = Pattern.compile(
            "^\\s*data\\s+\"([^\"]+)\"\\s+\"([^\"]+)\"\\s*\\{");
    private static final Pattern TF_VARIABLE = Pattern.compile(
            "^\\s*variable\\s+\"([^\"]+)\"\\s*\\{");
    private static final Pattern TF_OUTPUT = Pattern.compile(
            "^\\s*output\\s+\"([^\"]+)\"\\s*\\{");
    private static final Pattern TF_MODULE = Pattern.compile(
            "^\\s*module\\s+\"([^\"]+)\"\\s*\\{");
    private static final Pattern TF_LOCALS = Pattern.compile(
            "^\\s*locals\\s*\\{");
    private static final Pattern TF_PROVIDER = Pattern.compile(
            "^\\s*provider\\s+\"([^\"]+)\"\\s*\\{");
    private static final Pattern HCL_BLOCK = Pattern.compile(
            "^\\s*(\\w+)\\s+(?:\"([^\"]+)\"\\s+)?(?:\"([^\"]+)\"\\s+)?\\{");

    // -------------------------------------------------------------------------
    // Protobuf
    // -------------------------------------------------------------------------
    private static final Pattern PROTO_PACKAGE = Pattern.compile(
            "^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern PROTO_IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:public\\s+|weak\\s+)?\"([^\"]+)\"\\s*;");
    private static final Pattern PROTO_MESSAGE = Pattern.compile(
            "^\\s*message\\s+(\\w+)\\s*\\{");
    private static final Pattern PROTO_ENUM = Pattern.compile(
            "^\\s*enum\\s+(\\w+)\\s*\\{");
    private static final Pattern PROTO_SERVICE = Pattern.compile(
            "^\\s*service\\s+(\\w+)\\s*\\{");
    private static final Pattern PROTO_RPC = Pattern.compile(
            "^\\s*rpc\\s+(\\w+)\\s*\\(([^)]+)\\)\\s*returns\\s*\\(([^)]+)\\)");

    // -------------------------------------------------------------------------
    // GraphQL
    // -------------------------------------------------------------------------
    private static final Pattern GQL_TYPE = Pattern.compile(
            "^\\s*type\\s+(\\w+)(?:\\s+implements\\s+([\\w&\\s]+))?\\s*\\{");
    private static final Pattern GQL_INPUT = Pattern.compile(
            "^\\s*input\\s+(\\w+)\\s*\\{");
    private static final Pattern GQL_INTERFACE = Pattern.compile(
            "^\\s*interface\\s+(\\w+)\\s*\\{");
    private static final Pattern GQL_ENUM = Pattern.compile(
            "^\\s*enum\\s+(\\w+)\\s*\\{");
    private static final Pattern GQL_UNION = Pattern.compile(
            "^\\s*union\\s+(\\w+)\\s*=\\s*(.+)");
    private static final Pattern GQL_OPERATION = Pattern.compile(
            "^\\s*(query|mutation|subscription)\\s+(\\w+)");
    private static final Pattern GQL_FRAGMENT = Pattern.compile(
            "^\\s*fragment\\s+(\\w+)\\s+on\\s+(\\w+)\\s*\\{");
    private static final Pattern GQL_SCHEMA = Pattern.compile(
            "^\\s*schema\\s*\\{");

    // -------------------------------------------------------------------------
    // Haskell
    // -------------------------------------------------------------------------
    private static final Pattern HS_MODULE = Pattern.compile(
            "^\\s*module\\s+([\\w.]+)(?:\\s+\\(|\\s+where|$)");
    private static final Pattern HS_IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:qualified\\s+)?([\\w.]+)(?:\\s+as\\s+(\\w+))?(?:\\s+\\(|$)");
    private static final Pattern HS_DATA = Pattern.compile(
            "^\\s*(?:newtype|data)\\s+(\\w+)(?:[^=]*)=");
    private static final Pattern HS_TYPE_ALIAS = Pattern.compile(
            "^\\s*type\\s+(\\w+)(?:[\\w\\s]*)=");
    private static final Pattern HS_CLASS = Pattern.compile(
            "^\\s*class\\s+(?:[^=]*)\\b(\\w+)\\b[^=]*where");
    private static final Pattern HS_INSTANCE = Pattern.compile(
            "^\\s*instance\\s+(?:[^=]*)\\b(\\w+)\\b\\s+([\\w()]+)(?:\\s+where|$)");
    private static final Pattern HS_SIG = Pattern.compile(
            "^\\s*(\\w+)\\s+::\\s+(.+)");
    private static final Pattern HS_FUNCTION = Pattern.compile(
            "^\\s*(\\w+)(?:\\s+[^=\\|]*)=[^>]");

    // -------------------------------------------------------------------------
    // OCaml
    // -------------------------------------------------------------------------
    private static final Pattern ML_MODULE = Pattern.compile(
            "^\\s*module\\s+(\\w+)(?:\\s*:\\s*[\\w.]+)?\\s*=");
    private static final Pattern ML_OPEN = Pattern.compile(
            "^\\s*open\\s+([\\w.]+)");
    private static final Pattern ML_LET = Pattern.compile(
            "^\\s*let\\s+(?:rec\\s+)?(\\w+)(?:\\s|=)");
    private static final Pattern ML_TYPE = Pattern.compile(
            "^\\s*type\\s+(?:[\\w\\s']+\\s+)?(\\w+)\\s*=");
    private static final Pattern ML_VAL = Pattern.compile(
            "^\\s*val\\s+(\\w+)\\s*:");
    private static final Pattern ML_EXCEPTION = Pattern.compile(
            "^\\s*exception\\s+(\\w+)(?:\\s+of\\s+.+)?");

    // -------------------------------------------------------------------------
    // Elixir
    // -------------------------------------------------------------------------
    private static final Pattern EX_DEFMODULE = Pattern.compile(
            "^\\s*defmodule\\s+([\\w.]+)\\s+do");
    private static final Pattern EX_DEF = Pattern.compile(
            "^\\s*(def|defp|defmacro|defmacrop)\\s+(\\w+)(?:\\s*\\(([^)]*)\\))?\\s+do");
    private static final Pattern EX_USE = Pattern.compile(
            "^\\s*use\\s+([\\w.,\\s]+)");
    private static final Pattern EX_IMPORT = Pattern.compile(
            "^\\s*import\\s+([\\w.]+)");
    private static final Pattern EX_ALIAS = Pattern.compile(
            "^\\s*alias\\s+([\\w.,\\s{}]+)");
    private static final Pattern EX_REQUIRE = Pattern.compile(
            "^\\s*require\\s+([\\w.]+)");

    // -------------------------------------------------------------------------
    // Erlang
    // -------------------------------------------------------------------------
    private static final Pattern ERL_MODULE = Pattern.compile(
            "^\\s*-module\\s*\\(\\s*(\\w+)\\s*\\)\\s*\\.");
    private static final Pattern ERL_EXPORT = Pattern.compile(
            "^\\s*-export\\s*\\(\\s*\\[([^]]+)]\\s*\\)\\s*\\.");
    private static final Pattern ERL_IMPORT = Pattern.compile(
            "^\\s*-import\\s*\\(\\s*(\\w+)\\s*,\\s*\\[([^]]+)]\\s*\\)\\s*\\.");
    private static final Pattern ERL_FUNCTION = Pattern.compile(
            "^\\s*(\\w+)\\s*\\([^)]*\\)\\s*(?:when\\s+[^->]+)?->");
    private static final Pattern ERL_RECORD = Pattern.compile(
            "^\\s*-record\\s*\\(\\s*(\\w+)\\s*,");
    private static final Pattern ERL_TYPE = Pattern.compile(
            "^\\s*-(?:type|opaque)\\s+(\\w+)\\s*\\(");

    // -------------------------------------------------------------------------
    // R
    // -------------------------------------------------------------------------
    private static final Pattern R_FUNCTION = Pattern.compile(
            "^\\s*(\\w+)\\s*<-\\s*function\\s*\\(");
    private static final Pattern R_LIBRARY = Pattern.compile(
            "^\\s*(?:library|require)\\s*\\(\\s*([\\w.]+)");
    private static final Pattern R_SET_CLASS = Pattern.compile(
            "^\\s*setClass\\s*\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern R_SET_GENERIC = Pattern.compile(
            "^\\s*setGeneric\\s*\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern R_SET_METHOD = Pattern.compile(
            "^\\s*setMethod\\s*\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern R_ASSIGN = Pattern.compile(
            "^\\s*(\\w+)\\s*<-\\s*(?!function\\s*\\()");

    // -------------------------------------------------------------------------
    // Julia
    // -------------------------------------------------------------------------
    private static final Pattern JL_MODULE = Pattern.compile(
            "^\\s*module\\s+(\\w+)");
    private static final Pattern JL_USING = Pattern.compile(
            "^\\s*using\\s+([\\w.,:\\s]+)");
    private static final Pattern JL_IMPORT = Pattern.compile(
            "^\\s*import\\s+([\\w.,:\\s]+)");
    private static final Pattern JL_FUNCTION = Pattern.compile(
            "^\\s*(?:function|Base\\.@kwdef)\\s+(\\w+)(?:\\s*\\(|$)");
    private static final Pattern JL_STRUCT = Pattern.compile(
            "^\\s*(?:mutable\\s+)?struct\\s+(\\w+)(?:<:[\\w.]+)?");
    private static final Pattern JL_ABSTRACT = Pattern.compile(
            "^\\s*abstract\\s+type\\s+(\\w+)(?:<:[\\w.]+)?");
    private static final Pattern JL_MACRO = Pattern.compile(
            "^\\s*macro\\s+(\\w+)\\s*\\(");
    private static final Pattern JL_CONST = Pattern.compile(
            "^\\s*const\\s+(\\w+)\\s*=");

    // -------------------------------------------------------------------------
    // Clojure
    // -------------------------------------------------------------------------
    private static final Pattern CLJ_NS = Pattern.compile(
            "^\\s*\\(ns\\s+([\\w./-]+)");
    private static final Pattern CLJ_DEFN = Pattern.compile(
            "^\\s*\\(defn-?\\s+(\\w[\\w?!*-]*)");
    private static final Pattern CLJ_DEF = Pattern.compile(
            "^\\s*\\(def\\s+(\\w[\\w?!*-]*)");
    private static final Pattern CLJ_DEFMACRO = Pattern.compile(
            "^\\s*\\(defmacro\\s+(\\w[\\w?!*-]*)");
    private static final Pattern CLJ_DEFPROTOCOL = Pattern.compile(
            "^\\s*\\(defprotocol\\s+(\\w[\\w?!*-]*)");
    private static final Pattern CLJ_DEFRECORD = Pattern.compile(
            "^\\s*\\(defrecord\\s+(\\w[\\w?!*-]*)");
    private static final Pattern CLJ_DEFTYPE = Pattern.compile(
            "^\\s*\\(deftype\\s+(\\w[\\w?!*-]*)");
    private static final Pattern CLJ_REQUIRE = Pattern.compile(
            "^\\s*\\(require\\s+'?\\[([\\w./]+)");

    // =========================================================================

    @Override
    public Set<String> supportedLanguages() {
        return SUPPORTED;
    }

    @Override
    public ExtractionOutput parse(String[] lines, String filePath, String projectId, String language) {
        List<CodeEntity> entities = new ArrayList<>();
        List<RelationTriple> relations = new ArrayList<>();

        // FILE entity is created by CodeEntityExtractor — not duplicated here.

        switch (language) {
            case "sql"        -> parseSql(lines, filePath, projectId, entities, relations);
            case "bash"       -> parseBash(lines, filePath, projectId, entities, relations);
            case "powershell" -> parsePowerShell(lines, filePath, projectId, entities, relations);
            case "make"       -> parseMake(lines, filePath, projectId, entities, relations);
            case "dockerfile" -> parseDockerfile(lines, filePath, projectId, entities, relations);
            case "terraform", "hcl" -> parseTerraform(lines, filePath, projectId, entities, relations, language);
            case "protobuf"   -> parseProtobuf(lines, filePath, projectId, entities, relations);
            case "graphql"    -> parseGraphql(lines, filePath, projectId, entities, relations);
            case "haskell"    -> parseHaskell(lines, filePath, projectId, entities, relations);
            case "ocaml"      -> parseOcaml(lines, filePath, projectId, entities, relations);
            case "elixir"     -> parseElixir(lines, filePath, projectId, entities, relations);
            case "erlang"     -> parseErlang(lines, filePath, projectId, entities, relations);
            case "r"          -> parseR(lines, filePath, projectId, entities, relations);
            case "julia"      -> parseJulia(lines, filePath, projectId, entities, relations);
            case "clojure"    -> parseClojure(lines, filePath, projectId, entities, relations);
        }

        return new ExtractionOutput(entities, relations);
    }

    // =========================================================================
    // SQL
    // =========================================================================

    private void parseSql(String[] lines, String filePath, String projectId,
                          List<CodeEntity> entities, List<RelationTriple> relations) {
        Set<String> referencedTables = new LinkedHashSet<>();
        boolean inBlockComment = false;
        String currentTableName = null;
        String currentTableFqn = null;
        boolean inCreateTableBody = false;
        int parenDepth = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // --- SQL comment skipping ---
            if (inBlockComment) {
                int endIdx = line.indexOf("*/");
                if (endIdx >= 0) {
                    inBlockComment = false;
                    line = line.substring(endIdx + 2);
                } else {
                    continue;
                }
            }
            // Strip inline block comments
            while (line.contains("/*")) {
                int startIdx = line.indexOf("/*");
                int endIdx = line.indexOf("*/", startIdx + 2);
                if (endIdx >= 0) {
                    line = line.substring(0, startIdx) + line.substring(endIdx + 2);
                } else {
                    line = line.substring(0, startIdx);
                    inBlockComment = true;
                    break;
                }
            }
            // Strip single-line comments
            int dashIdx = line.indexOf("--");
            if (dashIdx >= 0) {
                // Make sure it's not inside a quoted string (simple heuristic)
                boolean inQuote = false;
                for (int c = 0; c < dashIdx; c++) {
                    if (line.charAt(c) == '\'') inQuote = !inQuote;
                }
                if (!inQuote) line = line.substring(0, dashIdx);
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // --- Track CREATE TABLE body for column & foreign key extraction ---
            if (inCreateTableBody) {
                for (int c = 0; c < line.length(); c++) {
                    if (line.charAt(c) == '(') parenDepth++;
                    else if (line.charAt(c) == ')') parenDepth--;
                }
                if (parenDepth <= 0) {
                    inCreateTableBody = false;
                    currentTableName = null;
                    currentTableFqn = null;
                    parenDepth = 0;
                    continue;
                }

                // Column definitions inside CREATE TABLE
                Matcher mc = SQL_COLUMN_DEF.matcher(trimmed);
                if (mc.find()) {
                    String colName = stripQuotes(mc.group(1));
                    // Skip SQL keywords that look like column names
                    if (!colName.equalsIgnoreCase("PRIMARY") && !colName.equalsIgnoreCase("UNIQUE") &&
                        !colName.equalsIgnoreCase("CHECK") && !colName.equalsIgnoreCase("CONSTRAINT") &&
                        !colName.equalsIgnoreCase("FOREIGN") && !colName.equalsIgnoreCase("INDEX")) {
                        String colFqn = currentTableFqn + "." + colName;
                        entities.add(entity(projectId, CodeEntityType.FIELD, colName, colFqn, filePath, "sql", i + 1, i + 1, trimmed.replaceAll(",\\s*$", ""), filePath));
                        relations.add(rel(currentTableFqn, colFqn, CodeRelationType.CONTAINS));
                    }
                }

                // FOREIGN KEY ... REFERENCES or inline REFERENCES
                Matcher mfk = SQL_FOREIGN_KEY.matcher(trimmed);
                if (mfk.find()) {
                    String refTable = stripQuotes(mfk.group(1));
                    relations.add(rel(currentTableFqn, filePath + "#table:" + refTable, CodeRelationType.DEPENDS_ON));
                } else {
                    Matcher mref = SQL_REFERENCES_INLINE.matcher(trimmed);
                    if (mref.find()) {
                        String refTable = stripQuotes(mref.group(1));
                        relations.add(rel(currentTableFqn, filePath + "#table:" + refTable, CodeRelationType.DEPENDS_ON));
                    }
                }
                continue;
            }

            // --- WITH ... AS (CTE extraction) ---
            if (trimmed.toUpperCase().startsWith("WITH ")) {
                Matcher mw = SQL_CTE.matcher(trimmed);
                while (mw.find()) {
                    String cteName = mw.group(1);
                    if (!cteName.equalsIgnoreCase("WITH") && !cteName.equalsIgnoreCase("RECURSIVE")) {
                        String cteFqn = filePath + "#cte:" + cteName;
                        entities.add(entity(projectId, CodeEntityType.FUNCTION, cteName, cteFqn, filePath, "sql", i + 1, i + 1, trimmed, filePath));
                        relations.add(rel(filePath, cteFqn, CodeRelationType.CONTAINS));
                    }
                }
            }

            // --- CREATE TABLE ---
            Matcher m = SQL_CREATE_TABLE.matcher(line);
            if (m.find()) {
                String name = stripQuotes(m.group(1));
                String fqn = filePath + "#table:" + name;
                entities.add(entity(projectId, CodeEntityType.CLASS, name, fqn, filePath, "sql", i + 1, i + 1, trimmed, filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                // Enter body parsing if there's an opening paren
                if (line.contains("(")) {
                    inCreateTableBody = true;
                    currentTableName = name;
                    currentTableFqn = fqn;
                    parenDepth = 0;
                    for (int c = 0; c < line.length(); c++) {
                        if (line.charAt(c) == '(') parenDepth++;
                        else if (line.charAt(c) == ')') parenDepth--;
                    }
                    if (parenDepth <= 0) {
                        inCreateTableBody = false;
                        currentTableName = null;
                        currentTableFqn = null;
                    }
                }
                continue;
            }

            // --- CREATE VIEW ---
            m = SQL_CREATE_VIEW.matcher(line);
            if (m.find()) {
                String name = stripQuotes(m.group(1));
                String fqn = filePath + "#view:" + name;
                entities.add(entity(projectId, CodeEntityType.CLASS, name, fqn, filePath, "sql", i + 1, i + 1, trimmed, filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            // --- CREATE INDEX ---
            m = SQL_CREATE_INDEX.matcher(line);
            if (m.find()) {
                String indexName = stripQuotes(m.group(1));
                String tableName = stripQuotes(m.group(2));
                String fqn = filePath + "#index:" + indexName;
                entities.add(entity(projectId, CodeEntityType.CONSTANT, indexName, fqn, filePath, "sql", i + 1, i + 1, trimmed, filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                relations.add(rel(fqn, filePath + "#table:" + tableName, CodeRelationType.DEPENDS_ON));
                continue;
            }

            // --- CREATE FUNCTION ---
            m = SQL_CREATE_FUNCTION.matcher(line);
            if (m.find()) {
                String name = stripQuotes(m.group(1));
                String fqn = filePath + "#func:" + name;
                entities.add(entity(projectId, CodeEntityType.FUNCTION, name, fqn, filePath, "sql", i + 1, i + 1, trimmed, filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            // --- CREATE PROCEDURE ---
            m = SQL_CREATE_PROCEDURE.matcher(line);
            if (m.find()) {
                String name = stripQuotes(m.group(1));
                String fqn = filePath + "#proc:" + name;
                entities.add(entity(projectId, CodeEntityType.FUNCTION, name, fqn, filePath, "sql", i + 1, i + 1, trimmed, filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            // --- CREATE TRIGGER (may span multiple lines) ---
            m = SQL_CREATE_TRIGGER.matcher(line);
            if (m.find()) {
                String triggerName = stripQuotes(m.group(1));
                String tableName = stripQuotes(m.group(2));
                String fqn = filePath + "#trigger:" + triggerName;
                entities.add(entity(projectId, CodeEntityType.FUNCTION, triggerName, fqn, filePath, "sql", i + 1, i + 1, trimmed, filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                relations.add(rel(fqn, filePath + "#table:" + tableName, CodeRelationType.DEPENDS_ON));
                continue;
            }
            // Multi-line CREATE TRIGGER: name on one line, BEFORE/AFTER...ON on next
            if (trimmed.toUpperCase().matches("^CREATE\\s+(?:OR\\s+REPLACE\\s+)?TRIGGER\\s+\\S+\\s*$")) {
                Matcher mt = Pattern.compile("(?i)^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?TRIGGER\\s+([\\w`\"]+)").matcher(trimmed);
                if (mt.find()) {
                    String triggerName = stripQuotes(mt.group(1));
                    // Look ahead for the ON clause
                    String tableName = null;
                    for (int j = i + 1; j < Math.min(i + 5, lines.length); j++) {
                        Matcher mon = Pattern.compile("(?i)\\bON\\s+(?:[\\w.`\"\\[\\]]+\\.)?([\\w`\"\\[\\]]+)").matcher(lines[j]);
                        if (mon.find()) {
                            tableName = stripQuotes(mon.group(1));
                            break;
                        }
                    }
                    String fqn = filePath + "#trigger:" + triggerName;
                    entities.add(entity(projectId, CodeEntityType.FUNCTION, triggerName, fqn, filePath, "sql", i + 1, i + 1, trimmed, filePath));
                    relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                    if (tableName != null) {
                        relations.add(rel(fqn, filePath + "#table:" + tableName, CodeRelationType.DEPENDS_ON));
                    }
                    continue;
                }
            }

            // --- ALTER TABLE ---
            m = SQL_ALTER_TABLE.matcher(line);
            if (m.find()) {
                String name = stripQuotes(m.group(1));
                referencedTables.add(name);
                // Check for FOREIGN KEY in ALTER TABLE line
                Matcher mfk = SQL_FOREIGN_KEY.matcher(line);
                if (mfk.find()) {
                    String refTable = stripQuotes(mfk.group(1));
                    relations.add(rel(filePath + "#table:" + name, filePath + "#table:" + refTable, CodeRelationType.DEPENDS_ON));
                }
                continue;
            }

            // --- INSERT INTO ---
            m = SQL_INSERT_INTO.matcher(line);
            if (m.find()) {
                String name = stripQuotes(m.group(1));
                referencedTables.add(name);
                continue;
            }

            // --- UPDATE ---
            m = SQL_UPDATE.matcher(line);
            if (m.find()) {
                String name = stripQuotes(m.group(1));
                referencedTables.add(name);
                continue;
            }

            // --- Table references — FROM / JOIN ---
            Matcher mf = SQL_FROM.matcher(line);
            while (mf.find()) {
                String t = stripQuotes(mf.group(1));
                if (!t.equalsIgnoreCase("dual")) referencedTables.add(t);
            }
            Matcher mj = SQL_JOIN.matcher(line);
            while (mj.find()) {
                referencedTables.add(stripQuotes(mj.group(1)));
            }
        }

        for (String table : referencedTables) {
            relations.add(rel(filePath, table, CodeRelationType.DEPENDS_ON));
        }
    }

    // =========================================================================
    // Bash
    // =========================================================================

    private void parseBash(String[] lines, String filePath, String projectId,
                           List<CodeEntity> entities, List<RelationTriple> relations) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("#")) continue;

            // function keyword form: function name() { or function name {
            Matcher m = BASH_FUNCTION_KW.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = filePath + "#" + name;
                entities.add(entity(projectId, CodeEntityType.FUNCTION, name, fqn, filePath, "bash", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            // plain form: name() {
            m = BASH_FUNCTION_PLAIN.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                // skip common false-positives: if/while/for/until/select
                if (!Set.of("if", "while", "for", "until", "select", "case").contains(name)) {
                    String fqn = filePath + "#" + name;
                    entities.add(entity(projectId, CodeEntityType.FUNCTION, name, fqn, filePath, "bash", i + 1, i + 1, line.trim(), filePath));
                    relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                }
                continue;
            }

            m = BASH_SOURCE.matcher(line);
            if (m.find()) {
                String src = m.group(1);
                entities.add(entity(projectId, CodeEntityType.IMPORT, src, src, filePath, "bash", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, src, CodeRelationType.IMPORTS));
                continue;
            }

            m = BASH_ALIAS.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = filePath + "#alias:" + name;
                entities.add(entity(projectId, CodeEntityType.VARIABLE, name, fqn, filePath, "bash", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = BASH_EXPORT.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = filePath + "#export:" + name;
                entities.add(entity(projectId, CodeEntityType.VARIABLE, name, fqn, filePath, "bash", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
            }
        }
    }

    // =========================================================================
    // PowerShell
    // =========================================================================

    private void parsePowerShell(String[] lines, String filePath, String projectId,
                                 List<CodeEntity> entities, List<RelationTriple> relations) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("#")) continue;

            Matcher m = PS_FUNCTION.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = filePath + "#" + name;
                entities.add(entity(projectId, CodeEntityType.FUNCTION, name, fqn, filePath, "powershell", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = PS_IMPORT_MODULE.matcher(line);
            if (m.find()) {
                String mod = m.group(1).trim();
                entities.add(entity(projectId, CodeEntityType.IMPORT, mod, mod, filePath, "powershell", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, mod, CodeRelationType.IMPORTS));
            }
        }
    }

    // =========================================================================
    // Make
    // =========================================================================

    private void parseMake(String[] lines, String filePath, String projectId,
                           List<CodeEntity> entities, List<RelationTriple> relations) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("#")) continue;
            // Skip lines starting with a tab (recipe lines)
            if (!line.isEmpty() && line.charAt(0) == '\t') continue;

            Matcher m = MAKE_TARGET.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                // Skip variable assignments that regex might accidentally match
                if (name.contains("=") || name.equals(".PHONY") || name.equals(".DEFAULT_GOAL")) continue;
                String fqn = filePath + "#target:" + name;
                String rest = line.contains(":") ? line.substring(line.indexOf(':') + 1).trim() : "";
                entities.add(entity(projectId, CodeEntityType.FUNCTION, name, fqn, filePath, "make", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                // Prerequisite targets
                for (String dep : rest.split("\\s+")) {
                    dep = dep.trim();
                    if (!dep.isEmpty() && !dep.startsWith("#")) {
                        relations.add(rel(fqn, filePath + "#target:" + dep, CodeRelationType.DEPENDS_ON));
                    }
                }
                continue;
            }

            m = MAKE_VARIABLE.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = filePath + "#var:" + name;
                entities.add(entity(projectId, CodeEntityType.VARIABLE, name, fqn, filePath, "make", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
            }
        }
    }

    // =========================================================================
    // Dockerfile
    // =========================================================================

    private void parseDockerfile(String[] lines, String filePath, String projectId,
                                 List<CodeEntity> entities, List<RelationTriple> relations) {
        int stageIndex = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("#")) continue;

            Matcher m = DOCKER_FROM.matcher(line);
            if (m.find()) {
                String image = m.group(1);
                String alias = m.group(2) != null ? m.group(2) : ("stage" + stageIndex++);
                String fqn = filePath + "#stage:" + alias;
                entities.add(entity(projectId, CodeEntityType.MODULE, alias, fqn, filePath, "dockerfile", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                relations.add(rel(fqn, image, CodeRelationType.DEPENDS_ON));
                continue;
            }

            m = DOCKER_EXPOSE.matcher(line);
            if (m.find()) {
                String port = m.group(1);
                String fqn = filePath + "#expose:" + port;
                entities.add(entity(projectId, CodeEntityType.CONSTANT, port, fqn, filePath, "dockerfile", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = DOCKER_ARG.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = filePath + "#arg:" + name;
                entities.add(entity(projectId, CodeEntityType.VARIABLE, name, fqn, filePath, "dockerfile", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = DOCKER_COPY.matcher(line);
            if (m.find()) {
                String src = m.group(1).trim().split("\\s+")[0];
                relations.add(rel(filePath, src, CodeRelationType.DEPENDS_ON));
                continue;
            }

            m = DOCKER_RUN.matcher(line);
            if (m.find()) {
                // Capture RUN commands as function-like entities for searchability
                String cmd = m.group(1).trim();
                if (!cmd.isEmpty()) {
                    String fqn = filePath + "#run:" + i;
                    entities.add(entity(projectId, CodeEntityType.FUNCTION, "RUN@" + (i + 1), fqn, filePath, "dockerfile", i + 1, i + 1, cmd, filePath));
                    relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                }
                continue;
            }

            m = DOCKER_ENTRYPOINT.matcher(line);
            if (m.find()) {
                String sig = m.group(1).trim();
                String fqn = filePath + "#entrypoint";
                entities.add(entity(projectId, CodeEntityType.FUNCTION, "ENTRYPOINT", fqn, filePath, "dockerfile", i + 1, i + 1, sig, filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = DOCKER_CMD.matcher(line);
            if (m.find()) {
                String sig = m.group(1).trim();
                String fqn = filePath + "#cmd";
                entities.add(entity(projectId, CodeEntityType.FUNCTION, "CMD", fqn, filePath, "dockerfile", i + 1, i + 1, sig, filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
            }
        }
    }

    // =========================================================================
    // Terraform / HCL
    // =========================================================================

    private void parseTerraform(String[] lines, String filePath, String projectId,
                                List<CodeEntity> entities, List<RelationTriple> relations,
                                String language) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("#") || line.trim().startsWith("//")) continue;

            Matcher m = TF_RESOURCE.matcher(line);
            if (m.find()) {
                String resType = m.group(1);
                String resName = m.group(2);
                String name = resType + "." + resName;
                String fqn = filePath + "#resource:" + name;
                entities.add(entity(projectId, CodeEntityType.CLASS, name, fqn, filePath, language, i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = TF_DATA.matcher(line);
            if (m.find()) {
                String dataType = m.group(1);
                String dataName = m.group(2);
                String name = "data." + dataType + "." + dataName;
                String fqn = filePath + "#data:" + dataType + "." + dataName;
                entities.add(entity(projectId, CodeEntityType.CLASS, name, fqn, filePath, language, i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = TF_VARIABLE.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = filePath + "#var:" + name;
                entities.add(entity(projectId, CodeEntityType.VARIABLE, name, fqn, filePath, language, i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = TF_OUTPUT.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = filePath + "#output:" + name;
                entities.add(entity(projectId, CodeEntityType.CONSTANT, name, fqn, filePath, language, i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = TF_MODULE.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = filePath + "#module:" + name;
                entities.add(entity(projectId, CodeEntityType.MODULE, name, fqn, filePath, language, i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = TF_PROVIDER.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = filePath + "#provider:" + name;
                entities.add(entity(projectId, CodeEntityType.MODULE, name, fqn, filePath, language, i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = TF_LOCALS.matcher(line);
            if (m.find()) {
                String fqn = filePath + "#locals:" + i;
                entities.add(entity(projectId, CodeEntityType.VARIABLE, "locals", fqn, filePath, language, i + 1, i + 1, "locals {", filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            // Generic HCL block (for .hcl files not matching terraform patterns)
            if ("hcl".equals(language)) {
                m = HCL_BLOCK.matcher(line);
                if (m.find()) {
                    String blockType = m.group(1);
                    String label1 = m.group(2) != null ? m.group(2) : "";
                    String label2 = m.group(3) != null ? m.group(3) : "";
                    String name = blockType + (label1.isEmpty() ? "" : "." + label1) + (label2.isEmpty() ? "" : "." + label2);
                    String fqn = filePath + "#" + name + ":" + i;
                    entities.add(entity(projectId, CodeEntityType.CLASS, name, fqn, filePath, language, i + 1, i + 1, line.trim(), filePath));
                    relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                }
            }
        }
    }

    // =========================================================================
    // Protobuf
    // =========================================================================

    private void parseProtobuf(String[] lines, String filePath, String projectId,
                               List<CodeEntity> entities, List<RelationTriple> relations) {
        String packageName = null;
        Deque<String> scopeStack = new ArrayDeque<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("//")) continue;

            // Track brace depth for scope
            for (char c : line.toCharArray()) {
                if (c == '{') scopeStack.push("?");
                else if (c == '}' && !scopeStack.isEmpty()) scopeStack.pop();
            }

            Matcher m = PROTO_PACKAGE.matcher(line);
            if (m.find()) {
                packageName = m.group(1);
                entities.add(entity(projectId, CodeEntityType.PACKAGE, packageName, packageName, filePath, "protobuf", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, packageName, CodeRelationType.CONTAINS));
                continue;
            }

            m = PROTO_IMPORT.matcher(line);
            if (m.find()) {
                String src = m.group(1);
                entities.add(entity(projectId, CodeEntityType.IMPORT, src, src, filePath, "protobuf", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, src, CodeRelationType.IMPORTS));
                continue;
            }

            m = PROTO_MESSAGE.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (packageName != null ? packageName + "." : "") + name;
                entities.add(entity(projectId, CodeEntityType.CLASS, name, fqn, filePath, "protobuf", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = PROTO_ENUM.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (packageName != null ? packageName + "." : "") + name;
                entities.add(entity(projectId, CodeEntityType.ENUM, name, fqn, filePath, "protobuf", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = PROTO_SERVICE.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (packageName != null ? packageName + "." : "") + name;
                entities.add(entity(projectId, CodeEntityType.INTERFACE, name, fqn, filePath, "protobuf", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = PROTO_RPC.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String input = m.group(2).trim().replace("stream ", "");
                String output = m.group(3).trim().replace("stream ", "");
                String fqn = (packageName != null ? packageName + "." : "") + name;
                entities.add(entity(projectId, CodeEntityType.METHOD, name, fqn, filePath, "protobuf", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                relations.add(rel(fqn, input, CodeRelationType.PARAMETER_TYPE));
                relations.add(rel(fqn, output, CodeRelationType.RETURNS));
            }
        }
    }

    // =========================================================================
    // GraphQL
    // =========================================================================

    private void parseGraphql(String[] lines, String filePath, String projectId,
                              List<CodeEntity> entities, List<RelationTriple> relations) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("#")) continue;

            Matcher m = GQL_SCHEMA.matcher(line);
            if (m.find()) {
                String fqn = filePath + "#schema";
                entities.add(entity(projectId, CodeEntityType.MODULE, "schema", fqn, filePath, "graphql", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = GQL_TYPE.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String implements_ = m.group(2);
                entities.add(entity(projectId, CodeEntityType.CLASS, name, name, filePath, "graphql", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, name, CodeRelationType.CONTAINS));
                if (implements_ != null) {
                    for (String iface : implements_.split("[&\\s]+")) {
                        iface = iface.trim();
                        if (!iface.isEmpty()) {
                            relations.add(rel(name, iface, CodeRelationType.IMPLEMENTS));
                        }
                    }
                }
                continue;
            }

            m = GQL_INPUT.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                entities.add(entity(projectId, CodeEntityType.CLASS, name, name, filePath, "graphql", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, name, CodeRelationType.CONTAINS));
                continue;
            }

            m = GQL_INTERFACE.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                entities.add(entity(projectId, CodeEntityType.INTERFACE, name, name, filePath, "graphql", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, name, CodeRelationType.CONTAINS));
                continue;
            }

            m = GQL_ENUM.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                entities.add(entity(projectId, CodeEntityType.ENUM, name, name, filePath, "graphql", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, name, CodeRelationType.CONTAINS));
                continue;
            }

            m = GQL_UNION.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String members = m.group(2);
                entities.add(entity(projectId, CodeEntityType.TYPE_ALIAS, name, name, filePath, "graphql", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, name, CodeRelationType.CONTAINS));
                for (String member : members.split("\\|")) {
                    member = member.trim();
                    if (!member.isEmpty()) {
                        relations.add(rel(name, member, CodeRelationType.DEPENDS_ON));
                    }
                }
                continue;
            }

            m = GQL_OPERATION.matcher(line);
            if (m.find()) {
                String opType = m.group(1);
                String opName = m.group(2);
                entities.add(entity(projectId, CodeEntityType.FUNCTION, opName, opName, filePath, "graphql", i + 1, i + 1, opType + " " + opName, filePath));
                relations.add(rel(filePath, opName, CodeRelationType.CONTAINS));
                continue;
            }

            m = GQL_FRAGMENT.matcher(line);
            if (m.find()) {
                String fragName = m.group(1);
                String onType = m.group(2);
                entities.add(entity(projectId, CodeEntityType.FUNCTION, fragName, fragName, filePath, "graphql", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fragName, CodeRelationType.CONTAINS));
                relations.add(rel(fragName, onType, CodeRelationType.DEPENDS_ON));
            }
        }
    }

    // =========================================================================
    // Haskell
    // =========================================================================

    private void parseHaskell(String[] lines, String filePath, String projectId,
                              List<CodeEntity> entities, List<RelationTriple> relations) {
        String moduleName = null;
        Set<String> declaredFunctions = new LinkedHashSet<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("--")) continue;

            Matcher m = HS_MODULE.matcher(line);
            if (m.find()) {
                moduleName = m.group(1);
                entities.add(entity(projectId, CodeEntityType.MODULE, moduleName, moduleName, filePath, "haskell", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, moduleName, CodeRelationType.CONTAINS));
                continue;
            }

            m = HS_IMPORT.matcher(line);
            if (m.find()) {
                String imported = m.group(1);
                entities.add(entity(projectId, CodeEntityType.IMPORT, imported, imported, filePath, "haskell", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, imported, CodeRelationType.IMPORTS));
                continue;
            }

            m = HS_DATA.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (moduleName != null ? moduleName + "." : "") + name;
                entities.add(entity(projectId, CodeEntityType.CLASS, name, fqn, filePath, "haskell", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = HS_TYPE_ALIAS.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (moduleName != null ? moduleName + "." : "") + name;
                entities.add(entity(projectId, CodeEntityType.TYPE_ALIAS, name, fqn, filePath, "haskell", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = HS_CLASS.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (moduleName != null ? moduleName + "." : "") + name;
                entities.add(entity(projectId, CodeEntityType.INTERFACE, name, fqn, filePath, "haskell", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = HS_INSTANCE.matcher(line);
            if (m.find()) {
                String className = m.group(1);
                String typeName = m.group(2);
                String fqn = (moduleName != null ? moduleName + "." : "") + "instance:" + className + "@" + typeName;
                entities.add(entity(projectId, CodeEntityType.CLASS, "instance " + className + " " + typeName, fqn, filePath, "haskell", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                relations.add(rel(fqn, className, CodeRelationType.IMPLEMENTS));
                continue;
            }

            // Type signatures — register and emit as function entities
            m = HS_SIG.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String sig = m.group(2).trim();
                if (!isHaskellKeyword(name)) {
                    String fqn = (moduleName != null ? moduleName + "." : "") + name;
                    entities.add(entity(projectId, CodeEntityType.FUNCTION, name, fqn, filePath, "haskell", i + 1, i + 1, name + " :: " + sig, filePath));
                    relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                    declaredFunctions.add(name);
                }
                continue;
            }

            // Top-level function definitions (only if no signature already seen)
            m = HS_FUNCTION.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                if (!isHaskellKeyword(name) && !declaredFunctions.contains(name)) {
                    String fqn = (moduleName != null ? moduleName + "." : "") + name;
                    entities.add(entity(projectId, CodeEntityType.FUNCTION, name, fqn, filePath, "haskell", i + 1, i + 1, line.trim(), filePath));
                    relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                    declaredFunctions.add(name);
                }
            }
        }
    }

    private boolean isHaskellKeyword(String name) {
        return Set.of("where", "let", "in", "if", "then", "else", "case", "of",
                "do", "import", "module", "data", "type", "newtype",
                "class", "instance", "deriving").contains(name);
    }

    // =========================================================================
    // OCaml
    // =========================================================================

    private void parseOcaml(String[] lines, String filePath, String projectId,
                            List<CodeEntity> entities, List<RelationTriple> relations) {
        String moduleName = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("(*") || line.trim().startsWith("(*")) continue;

            Matcher m = ML_MODULE.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                moduleName = name;
                entities.add(entity(projectId, CodeEntityType.MODULE, name, name, filePath, "ocaml", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, name, CodeRelationType.CONTAINS));
                continue;
            }

            m = ML_OPEN.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                entities.add(entity(projectId, CodeEntityType.IMPORT, name, name, filePath, "ocaml", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, name, CodeRelationType.IMPORTS));
                continue;
            }

            m = ML_TYPE.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (moduleName != null ? moduleName + "." : "") + name;
                entities.add(entity(projectId, CodeEntityType.TYPE_ALIAS, name, fqn, filePath, "ocaml", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = ML_EXCEPTION.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (moduleName != null ? moduleName + "." : "") + name;
                entities.add(entity(projectId, CodeEntityType.CLASS, name, fqn, filePath, "ocaml", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = ML_VAL.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (moduleName != null ? moduleName + "." : "") + name;
                entities.add(entity(projectId, CodeEntityType.FUNCTION, name, fqn, filePath, "ocaml", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = ML_LET.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                if (!isOcamlKeyword(name)) {
                    String fqn = (moduleName != null ? moduleName + "." : "") + name;
                    entities.add(entity(projectId, CodeEntityType.FUNCTION, name, fqn, filePath, "ocaml", i + 1, i + 1, line.trim(), filePath));
                    relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                }
            }
        }
    }

    private boolean isOcamlKeyword(String name) {
        return Set.of("in", "and", "fun", "function", "match", "with", "begin",
                "end", "if", "then", "else", "for", "while", "do", "done",
                "try", "exception", "raise", "module", "struct", "sig",
                "open", "include", "type", "val", "external").contains(name);
    }

    // =========================================================================
    // Elixir
    // =========================================================================

    private void parseElixir(String[] lines, String filePath, String projectId,
                             List<CodeEntity> entities, List<RelationTriple> relations) {
        String currentModule = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("#")) continue;

            Matcher m = EX_DEFMODULE.matcher(line);
            if (m.find()) {
                currentModule = m.group(1);
                entities.add(entity(projectId, CodeEntityType.MODULE, currentModule, currentModule, filePath, "elixir", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, currentModule, CodeRelationType.CONTAINS));
                continue;
            }

            m = EX_DEF.matcher(line);
            if (m.find()) {
                String defType = m.group(1);
                String name = m.group(2);
                String params = m.group(3) != null ? m.group(3) : "";
                String fqn = (currentModule != null ? currentModule + "." : "") + name;
                boolean isPrivate = defType.endsWith("p");
                CodeEntityType type = defType.startsWith("defmacro") ? CodeEntityType.FUNCTION : CodeEntityType.METHOD;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(type)
                        .name(name)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("elixir")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature(defType + " " + name + "(" + params + ")")
                        .parentFqn(currentModule != null ? currentModule : filePath)
                        .visibility(isPrivate ? "private" : "public")
                        .build());
                String parent = currentModule != null ? currentModule : filePath;
                relations.add(rel(parent, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = EX_USE.matcher(line);
            if (m.find()) {
                String dep = m.group(1).trim();
                relations.add(rel(currentModule != null ? currentModule : filePath, dep, CodeRelationType.DEPENDS_ON));
                continue;
            }

            m = EX_IMPORT.matcher(line);
            if (m.find()) {
                String imported = m.group(1).trim();
                entities.add(entity(projectId, CodeEntityType.IMPORT, imported, imported, filePath, "elixir", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, imported, CodeRelationType.IMPORTS));
                continue;
            }

            m = EX_ALIAS.matcher(line);
            if (m.find()) {
                String aliased = m.group(1).trim();
                entities.add(entity(projectId, CodeEntityType.IMPORT, aliased, aliased, filePath, "elixir", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, aliased, CodeRelationType.IMPORTS));
                continue;
            }

            m = EX_REQUIRE.matcher(line);
            if (m.find()) {
                String required = m.group(1).trim();
                relations.add(rel(currentModule != null ? currentModule : filePath, required, CodeRelationType.DEPENDS_ON));
            }
        }
    }

    // =========================================================================
    // Erlang
    // =========================================================================

    private void parseErlang(String[] lines, String filePath, String projectId,
                             List<CodeEntity> entities, List<RelationTriple> relations) {
        String moduleName = null;
        Set<String> seenFunctions = new LinkedHashSet<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("%")) continue;

            Matcher m = ERL_MODULE.matcher(line);
            if (m.find()) {
                moduleName = m.group(1);
                entities.add(entity(projectId, CodeEntityType.MODULE, moduleName, moduleName, filePath, "erlang", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, moduleName, CodeRelationType.CONTAINS));
                continue;
            }

            m = ERL_EXPORT.matcher(line);
            if (m.find()) {
                String exports = m.group(1);
                for (String export : exports.split(",")) {
                    export = export.trim();
                    if (export.contains("/")) {
                        String[] parts = export.split("/");
                        String funcName = parts[0].trim();
                        String arity = parts[1].trim();
                        String fqn = (moduleName != null ? moduleName + ":" : "") + funcName + "/" + arity;
                        entities.add(entity(projectId, CodeEntityType.FUNCTION, funcName + "/" + arity, fqn, filePath, "erlang", i + 1, i + 1, "-export([" + export + "])", filePath));
                        relations.add(rel(moduleName != null ? moduleName : filePath, fqn, CodeRelationType.CONTAINS));
                        seenFunctions.add(funcName);
                    }
                }
                continue;
            }

            m = ERL_IMPORT.matcher(line);
            if (m.find()) {
                String srcModule = m.group(1);
                entities.add(entity(projectId, CodeEntityType.IMPORT, srcModule, srcModule, filePath, "erlang", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, srcModule, CodeRelationType.IMPORTS));
                continue;
            }

            m = ERL_RECORD.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (moduleName != null ? moduleName + "#" : "") + name;
                entities.add(entity(projectId, CodeEntityType.CLASS, name, fqn, filePath, "erlang", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(moduleName != null ? moduleName : filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = ERL_TYPE.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (moduleName != null ? moduleName + "." : "") + name;
                entities.add(entity(projectId, CodeEntityType.TYPE_ALIAS, name, fqn, filePath, "erlang", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(moduleName != null ? moduleName : filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            // Function clause — only emit once per function name
            m = ERL_FUNCTION.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                if (!seenFunctions.contains(name) && !isErlangAttribute(name)) {
                    String fqn = (moduleName != null ? moduleName + ":" : "") + name;
                    entities.add(entity(projectId, CodeEntityType.FUNCTION, name, fqn, filePath, "erlang", i + 1, i + 1, line.trim(), filePath));
                    relations.add(rel(moduleName != null ? moduleName : filePath, fqn, CodeRelationType.CONTAINS));
                    seenFunctions.add(name);
                }
            }
        }
    }

    private boolean isErlangAttribute(String name) {
        return name.startsWith("-") || Set.of("module", "export", "import", "record",
                "type", "opaque", "spec", "callback", "behaviour", "behavior",
                "include", "include_lib", "ifdef", "ifndef", "endif", "define").contains(name);
    }

    // =========================================================================
    // R
    // =========================================================================

    private void parseR(String[] lines, String filePath, String projectId,
                        List<CodeEntity> entities, List<RelationTriple> relations) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("#")) continue;

            Matcher m = R_FUNCTION.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = filePath + "#" + name;
                entities.add(entity(projectId, CodeEntityType.FUNCTION, name, fqn, filePath, "r", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = R_LIBRARY.matcher(line);
            if (m.find()) {
                String pkg = m.group(1);
                entities.add(entity(projectId, CodeEntityType.IMPORT, pkg, pkg, filePath, "r", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, pkg, CodeRelationType.IMPORTS));
                continue;
            }

            m = R_SET_CLASS.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = filePath + "#S4:" + name;
                entities.add(entity(projectId, CodeEntityType.CLASS, name, fqn, filePath, "r", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = R_SET_GENERIC.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = filePath + "#generic:" + name;
                entities.add(entity(projectId, CodeEntityType.METHOD, name, fqn, filePath, "r", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = R_SET_METHOD.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = filePath + "#method:" + name;
                entities.add(entity(projectId, CodeEntityType.METHOD, name, fqn, filePath, "r", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            // Top-level variable assignments (exclude function-defining ones already caught)
            m = R_ASSIGN.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = filePath + "#var:" + name;
                entities.add(entity(projectId, CodeEntityType.VARIABLE, name, fqn, filePath, "r", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, fqn, CodeRelationType.CONTAINS));
            }
        }
    }

    // =========================================================================
    // Julia
    // =========================================================================

    private void parseJulia(String[] lines, String filePath, String projectId,
                            List<CodeEntity> entities, List<RelationTriple> relations) {
        String currentModule = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("#")) continue;

            Matcher m = JL_MODULE.matcher(line);
            if (m.find()) {
                currentModule = m.group(1);
                entities.add(entity(projectId, CodeEntityType.MODULE, currentModule, currentModule, filePath, "julia", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, currentModule, CodeRelationType.CONTAINS));
                continue;
            }

            m = JL_USING.matcher(line);
            if (m.find()) {
                String deps = m.group(1);
                for (String dep : deps.split(",")) {
                    dep = dep.trim().replaceAll(":.*", "");
                    if (!dep.isEmpty()) {
                        entities.add(entity(projectId, CodeEntityType.IMPORT, dep, dep, filePath, "julia", i + 1, i + 1, line.trim(), filePath));
                        relations.add(rel(filePath, dep, CodeRelationType.IMPORTS));
                    }
                }
                continue;
            }

            m = JL_IMPORT.matcher(line);
            if (m.find()) {
                String deps = m.group(1);
                for (String dep : deps.split(",")) {
                    dep = dep.trim().replaceAll(":.*", "");
                    if (!dep.isEmpty()) {
                        entities.add(entity(projectId, CodeEntityType.IMPORT, dep, dep, filePath, "julia", i + 1, i + 1, line.trim(), filePath));
                        relations.add(rel(filePath, dep, CodeRelationType.IMPORTS));
                    }
                }
                continue;
            }

            m = JL_FUNCTION.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (currentModule != null ? currentModule + "." : "") + name;
                entities.add(entity(projectId, CodeEntityType.FUNCTION, name, fqn, filePath, "julia", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(currentModule != null ? currentModule : filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = JL_STRUCT.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (currentModule != null ? currentModule + "." : "") + name;
                entities.add(entity(projectId, CodeEntityType.CLASS, name, fqn, filePath, "julia", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(currentModule != null ? currentModule : filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = JL_ABSTRACT.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (currentModule != null ? currentModule + "." : "") + name;
                entities.add(entity(projectId, CodeEntityType.INTERFACE, name, fqn, filePath, "julia", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(currentModule != null ? currentModule : filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = JL_MACRO.matcher(line);
            if (m.find()) {
                String name = "@" + m.group(1);
                String fqn = (currentModule != null ? currentModule + "." : "") + name;
                entities.add(entity(projectId, CodeEntityType.FUNCTION, name, fqn, filePath, "julia", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(currentModule != null ? currentModule : filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = JL_CONST.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (currentModule != null ? currentModule + "." : "") + name;
                entities.add(entity(projectId, CodeEntityType.CONSTANT, name, fqn, filePath, "julia", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(currentModule != null ? currentModule : filePath, fqn, CodeRelationType.CONTAINS));
            }
        }
    }

    // =========================================================================
    // Clojure
    // =========================================================================

    private void parseClojure(String[] lines, String filePath, String projectId,
                              List<CodeEntity> entities, List<RelationTriple> relations) {
        String currentNs = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith(";")) continue;

            Matcher m = CLJ_NS.matcher(line);
            if (m.find()) {
                currentNs = m.group(1);
                entities.add(entity(projectId, CodeEntityType.PACKAGE, currentNs, currentNs, filePath, "clojure", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, currentNs, CodeRelationType.CONTAINS));
                continue;
            }

            m = CLJ_REQUIRE.matcher(line);
            if (m.find()) {
                String req = m.group(1);
                entities.add(entity(projectId, CodeEntityType.IMPORT, req, req, filePath, "clojure", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(filePath, req, CodeRelationType.IMPORTS));
                continue;
            }

            m = CLJ_DEFN.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (currentNs != null ? currentNs + "/" : "") + name;
                entities.add(entity(projectId, CodeEntityType.FUNCTION, name, fqn, filePath, "clojure", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(currentNs != null ? currentNs : filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = CLJ_DEFMACRO.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (currentNs != null ? currentNs + "/" : "") + name;
                entities.add(entity(projectId, CodeEntityType.FUNCTION, name, fqn, filePath, "clojure", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(currentNs != null ? currentNs : filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = CLJ_DEFPROTOCOL.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (currentNs != null ? currentNs + "/" : "") + name;
                entities.add(entity(projectId, CodeEntityType.INTERFACE, name, fqn, filePath, "clojure", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(currentNs != null ? currentNs : filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = CLJ_DEFRECORD.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (currentNs != null ? currentNs + "/" : "") + name;
                entities.add(entity(projectId, CodeEntityType.RECORD, name, fqn, filePath, "clojure", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(currentNs != null ? currentNs : filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = CLJ_DEFTYPE.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = (currentNs != null ? currentNs + "/" : "") + name;
                entities.add(entity(projectId, CodeEntityType.CLASS, name, fqn, filePath, "clojure", i + 1, i + 1, line.trim(), filePath));
                relations.add(rel(currentNs != null ? currentNs : filePath, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            m = CLJ_DEF.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                // Skip defn/defmacro/defprotocol/defrecord/deftype — already handled above
                if (!line.trim().startsWith("(defn") && !line.trim().startsWith("(defmacro")
                        && !line.trim().startsWith("(defprotocol") && !line.trim().startsWith("(defrecord")
                        && !line.trim().startsWith("(deftype")) {
                    String fqn = (currentNs != null ? currentNs + "/" : "") + name;
                    entities.add(entity(projectId, CodeEntityType.VARIABLE, name, fqn, filePath, "clojure", i + 1, i + 1, line.trim(), filePath));
                    relations.add(rel(currentNs != null ? currentNs : filePath, fqn, CodeRelationType.CONTAINS));
                }
            }
        }
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    private CodeEntity entity(String projectId, CodeEntityType type, String name, String fqn,
                              String filePath, String language, int startLine, int endLine,
                              String signature, String parentFqn) {
        return CodeEntity.builder()
                .projectId(projectId)
                .entityType(type)
                .name(name)
                .fullyQualifiedName(fqn)
                .filePath(filePath)
                .language(language)
                .startLine(startLine)
                .endLine(endLine)
                .signature(signature)
                .parentFqn(parentFqn)
                .build();
    }

    private RelationTriple rel(String source, String target, CodeRelationType type) {
        return new RelationTriple(source, target, type);
    }

    /** Strip SQL-style quoting characters from an identifier */
    private String stripQuotes(String s) {
        if (s == null) return null;
        return s.replaceAll("[`\"\\[\\]]", "");
    }
}
