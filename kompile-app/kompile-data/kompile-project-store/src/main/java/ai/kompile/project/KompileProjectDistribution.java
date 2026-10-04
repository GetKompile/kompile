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
package ai.kompile.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Optional curated-release requirements, not a runtime capability declaration.
 * Setters permit incremental construction; validate before using a completed distribution.
 * Attaching one to a project manifest validates it automatically.
 */
public class KompileProjectDistribution {
    private static final Set<String> DELIVERIES = Set.of("native", "mixed", "jar");
    private static final Set<String> MATERIALIZATIONS = Set.of("bundle", "provision", "external");
    private static final Set<String> BUILD_TIERS = Set.of("assemble", "java", "native-image", "backend-source");

    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String delivery = "native";
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String materialization = "bundle";
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String buildTier = "assemble";
    private List<Target> targets = new ArrayList<>();
    private List<Software> software = new ArrayList<>();
    private Web web = new Web();

    public String getDelivery() { return delivery; }
    public void setDelivery(String delivery) { this.delivery = delivery; }
    public String getMaterialization() { return materialization; }
    public void setMaterialization(String materialization) { this.materialization = materialization; }
    public String getBuildTier() { return buildTier; }
    public void setBuildTier(String buildTier) { this.buildTier = buildTier; }
    public List<Target> getTargets() { return targets; }
    public void setTargets(List<Target> targets) {
        this.targets = targets == null ? null : new ArrayList<>(targets);
    }
    public List<Software> getSoftware() { return software; }
    public void setSoftware(List<Software> software) {
        this.software = software == null ? null : new ArrayList<>(software);
    }
    public Web getWeb() { return web; }
    public void setWeb(Web web) { this.web = web; }

    // The project mapper intentionally ignores unknown outer properties. These objects must not.
    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Unknown distribution property: " + name);
    }

    public void validate() {
        requireChoice("distribution.delivery", delivery, DELIVERIES);
        requireChoice("distribution.materialization", materialization, MATERIALIZATIONS);
        requireChoice("distribution.buildTier", buildTier, BUILD_TIERS);
        if (targets == null || software == null || web == null) {
            throw new IllegalArgumentException("distribution targets, software and web must not be null");
        }
        Set<String> targetIds = new HashSet<>();
        for (Target target : targets) {
            if (target == null) {
                throw new IllegalArgumentException("distribution.targets must not contain null");
            }
            requireText("target.id", target.id);
            requireUnique("target", target.id, targetIds);
            requireText("target.os", target.os);
            requireText("target.architecture", target.architecture);
            requireText("target.backend", target.backend);
        }
        Set<String> softwareIds = new HashSet<>();
        for (Software requirement : software) {
            if (requirement == null) {
                throw new IllegalArgumentException("distribution.software must not contain null");
            }
            requireText("software.id", requirement.id);
            requireUnique("software", requirement.id, softwareIds);
            requireText("software.component", requirement.component);
            requirePinnedVersion(requirement.version);
            if (requirement.path != null) {
                requireRelativePath(requirement.path);
            }
            if (requirement.materialization != null) {
                requireChoice("software.materialization", requirement.materialization, MATERIALIZATIONS);
            }
            if (requirement.buildTier != null) {
                requireChoice("software.buildTier", requirement.buildTier, BUILD_TIERS);
            }
        }
        requireText("web.bind", web.bind);
    }

    private static void requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void requireChoice(String field, String value, Set<String> choices) {
        if (value == null || !choices.contains(value)) {
            throw new IllegalArgumentException(field + " must be one of " + choices);
        }
    }

    private static void requireUnique(String kind, String id, Set<String> ids) {
        if (!ids.add(id)) {
            throw new IllegalArgumentException("Duplicate distribution " + kind + " id: " + id);
        }
    }

    private static void requirePinnedVersion(String version) {
        requireText("software.version", version);
        // Explicit local SNAPSHOT versions remain usable; floating aliases/ranges are not pins.
        if (Set.of("latest", "release", "stable").contains(version.toLowerCase(Locale.ROOT))
                || version.chars().anyMatch(Character::isWhitespace)
                || version.endsWith("+")
                || version.matches(".*[\\[\\](),*{}$^~].*")) {
            throw new IllegalArgumentException("software.version must be pinned: " + version);
        }
    }

    private static void requireRelativePath(String path) {
        requireText("software.path", path);
        // Check both separator forms independently of the host OS (including drives and UNC).
        String portable = path.replace('\\', '/');
        if (portable.startsWith("/") || portable.indexOf(':') >= 0 || Path.of(path).isAbsolute()) {
            throw new IllegalArgumentException("software.path must be relative: " + path);
        }
        for (String part : portable.split("/")) {
            if ("..".equals(part)) {
                throw new IllegalArgumentException("software.path must not traverse parents: " + path);
            }
        }
    }

    /** Artifact selection only; backend identifiers are deliberately open-ended. */
    public static class Target {
        @JsonDeserialize(using = StrictStringDeserializer.class)
        private String id;
        @JsonDeserialize(using = StrictStringDeserializer.class)
        private String os;
        @JsonDeserialize(using = StrictStringDeserializer.class)
        private String architecture;
        @JsonDeserialize(using = StrictStringDeserializer.class)
        private String backend;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getOs() { return os; }
        public void setOs(String os) { this.os = os; }
        public String getArchitecture() { return architecture; }
        public void setArchitecture(String architecture) { this.architecture = architecture; }
        public String getBackend() { return backend; }
        public void setBackend(String backend) { this.backend = backend; }

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("Unknown distribution target property: " + name);
        }
    }

    /** Software acquisition requirements, separate from project storage registrations. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Software {
        @JsonDeserialize(using = StrictStringDeserializer.class)
        private String id;
        @JsonDeserialize(using = StrictStringDeserializer.class)
        private String component;
        @JsonDeserialize(using = StrictStringDeserializer.class)
        private String version;
        @JsonDeserialize(using = StrictStringDeserializer.class)
        private String path;
        @JsonDeserialize(using = StrictStringDeserializer.class)
        private String materialization;
        @JsonDeserialize(using = StrictStringDeserializer.class)
        private String buildTier;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getComponent() { return component; }
        public void setComponent(String component) { this.component = component; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        /** Null means inherit the distribution-wide materialization. */
        public String getMaterialization() { return materialization; }
        public void setMaterialization(String materialization) { this.materialization = materialization; }
        /** Null means inherit the distribution-wide build tier. */
        public String getBuildTier() { return buildTier; }
        public void setBuildTier(String buildTier) { this.buildTier = buildTier; }

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("Unknown distribution software property: " + name);
        }
    }

    public static class Web {
        @JsonDeserialize(using = StrictBooleanDeserializer.class)
        @JsonSetter(nulls = Nulls.FAIL)
        private boolean enabled = true;
        @JsonDeserialize(using = StrictStringDeserializer.class)
        private String bind = "127.0.0.1";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBind() { return bind; }
        public void setBind(String bind) { this.bind = bind; }

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("Unknown distribution web property: " + name);
        }
    }

    /** Prevent Jackson scalar coercion, even on the lenient project mapper. */
    public static class StrictStringDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (String) context.handleUnexpectedToken(String.class, parser);
            }
            return parser.getText();
        }
    }

    public static class StrictBooleanDeserializer extends JsonDeserializer<Boolean> {
        @Override
        public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_TRUE) && !parser.hasToken(JsonToken.VALUE_FALSE)) {
                return (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
            }
            return parser.getBooleanValue();
        }
    }
}
