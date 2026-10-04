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

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KompileProjectDistributionTest {
    private static final String TARGET = """
            {"id":"desktop","os":"linux","architecture":"x86_64","backend":"vendor/custom:1.2"}
            """;
    private static final String SOFTWARE = """
            {"id":"chat","component":"kompile-app-chat","version":"1.2.3"}
            """;
    private final ObjectMapper mapper = JsonUtils.newStandardMapper();

    @Test
    void ordinaryProjectsKeepDistributionAbsentOrNull() throws Exception {
        assertFalse(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        for (String json : List.of("{}", "{\"distribution\":null}", "{\"futureOuterField\":true}")) {
            KompileProjectManifest project = mapper.readValue(json, KompileProjectManifest.class);
            assertNull(project.getDistribution());
            String encoded = mapper.writeValueAsString(project);
            assertFalse(mapper.readTree(encoded).has("distribution"));
            assertNull(mapper.readValue(encoded, KompileProjectManifest.class).getDistribution());
        }
        assertNull(new KompileProjectManifest().getDistribution());
    }

    @Test
    void presentDistributionDefaultsRoundTripWithoutInventingAssets() throws Exception {
        for (String json : List.of("{}", "{\"web\":{}}")) {
            KompileProjectManifest project = readProject(json);
            KompileProjectDistribution distribution = project.getDistribution();
            assertEquals("native", distribution.getDelivery());
            assertEquals("bundle", distribution.getMaterialization());
            assertEquals("assemble", distribution.getBuildTier());
            assertTrue(distribution.getTargets().isEmpty());
            assertTrue(distribution.getSoftware().isEmpty());
            assertTrue(distribution.getWeb().isEnabled());
            assertEquals("127.0.0.1", distribution.getWeb().getBind());
            assertDoesNotThrow(distribution::validate);
            assertRoundTrip(project);
        }
    }

    @Test
    void typedRequirementsAndOverridesRoundTripWithoutBackendCapabilityWhitelist() throws Exception {
        KompileProjectManifest project = readProject("""
                {
                  "delivery":"mixed", "materialization":"provision", "buildTier":"java",
                  "targets":[
                    {"id":"desktop","os":"linux","architecture":"x86_64","backend":"vendor/custom:1.2"},
                    {"id":"other","os":"custom-os","architecture":"custom-arch","backend":"unqualified-artifacts"}
                  ],
                  "software":[
                    {"id":"chat","component":"kompile-app-chat","version":"1.2.3",
                     "path":"runtime/chat","materialization":"bundle","buildTier":"native-image"},
                    {"id":"worker","component":"vendor-worker","version":"1.0.0-SNAPSHOT"}
                  ],
                  "web":{"enabled":false,"bind":"0.0.0.0"}
                }
                """);
        KompileProjectDistribution distribution = project.getDistribution();
        assertEquals("mixed", distribution.getDelivery());
        assertEquals("provision", distribution.getMaterialization());
        assertEquals("java", distribution.getBuildTier());
        assertEquals(2, distribution.getTargets().size());
        assertEquals("vendor/custom:1.2", distribution.getTargets().get(0).getBackend());
        assertEquals("custom-os", distribution.getTargets().get(1).getOs());
        assertEquals("custom-arch", distribution.getTargets().get(1).getArchitecture());
        KompileProjectDistribution.Software chat = distribution.getSoftware().get(0);
        assertEquals("kompile-app-chat", chat.getComponent());
        assertEquals("1.2.3", chat.getVersion());
        assertEquals("runtime/chat", chat.getPath());
        assertEquals("bundle", chat.getMaterialization());
        assertEquals("native-image", chat.getBuildTier());
        assertNull(distribution.getSoftware().get(1).getMaterialization());
        assertNull(distribution.getSoftware().get(1).getBuildTier());
        assertNull(distribution.getSoftware().get(1).getPath());
        assertFalse(distribution.getWeb().isEnabled());
        assertEquals("0.0.0.0", distribution.getWeb().getBind());
        assertRoundTrip(project);
    }

    @Test
    void allDocumentedEnumStringsAreAccepted() throws Exception {
        for (String delivery : List.of("native", "mixed", "jar")) {
            assertEquals(delivery, readProject("{\"delivery\":\"" + delivery + "\"}")
                    .getDistribution().getDelivery());
        }
        for (String materialization : List.of("bundle", "provision", "external")) {
            readProject("{\"materialization\":\"" + materialization + "\"}");
            readProject(withSoftware("materialization", materialization));
        }
        for (String tier : List.of("assemble", "java", "native-image", "backend-source")) {
            readProject("{\"buildTier\":\"" + tier + "\"}");
            readProject(withSoftware("buildTier", tier));
        }
    }

    @Test
    void unknownEnumValuesNeverBecomeDefaults() {
        for (String field : List.of("delivery", "materialization", "buildTier")) {
            for (String value : List.of("unsupported", "", "NATIVE", " native ")) {
                assertInvalid("{\"" + field + "\":\"" + value + "\"}");
            }
            assertInvalid("{\"" + field + "\":null}");
        }
        assertInvalid(withSoftware("materialization", "cached"));
        assertInvalid(withSoftware("buildTier", "compile"));
    }

    @Test
    void unknownPropertiesFailAtEveryDistributionLevelWithLenientOuterMapper() {
        for (String json : List.of(
                "{\"typo\":true}",
                "{\"targets\":[" + TARGET.strip().replace("}", ",\"typo\":true}") + "]}",
                "{\"software\":[" + SOFTWARE.strip().replace("}", ",\"typo\":true}") + "]}",
                "{\"web\":{\"typo\":true}}")) {
            assertInvalid(json);
        }
    }

    @Test
    void wrongJsonTypesAreRejectedWithoutScalarCoercion() {
        for (String json : List.of(
                "true", "17", "[]", "\"native\"",
                "{\"delivery\":false}", "{\"buildTier\":3}", "{\"materialization\":[]}",
                "{\"targets\":{}}", "{\"targets\":[true]}", "{\"software\":{}}",
                "{\"web\":[]}", "{\"web\":{\"enabled\":\"true\"}}",
                "{\"web\":{\"enabled\":1}}", "{\"web\":{\"enabled\":null}}",
                "{\"web\":{\"bind\":127}}")) {
            assertInvalid(json);
        }
        for (String field : List.of("id", "os", "architecture", "backend")) {
            assertInvalid("{\"targets\":[" + TARGET.strip().replaceFirst(
                    "\"" + field + "\":\"[^\"]*\"", "\"" + field + "\":123") + "]}");
        }
        for (String field : List.of("id", "component", "version", "path", "materialization", "buildTier")) {
            String requirement = SOFTWARE.strip().replace("}", ",\"" + field + "\":123}");
            // For required fields avoid duplicate JSON properties in the fixture.
            if (List.of("id", "component", "version").contains(field)) {
                requirement = SOFTWARE.strip().replaceFirst(
                        "\"" + field + "\":\"[^\"]*\"", "\"" + field + "\":123");
            }
            assertInvalid("{\"software\":[" + requirement + "]}");
        }
    }

    @Test
    void nullContainersAndEntriesCannotHideInvalidRequirements() {
        for (String json : List.of(
                "{\"targets\":null}", "{\"software\":null}", "{\"web\":null}",
                "{\"targets\":[null]}", "{\"software\":[null]}",
                "{\"web\":{\"bind\":null}}", "{\"web\":{\"bind\":\" \"}}")) {
            assertInvalid(json);
        }
    }

    @Test
    void targetAndSoftwareRequiredFieldsAreValidatedAfterDeserialization() {
        for (String field : List.of("id", "os", "architecture", "backend")) {
            for (String replacement : List.of("null", "\" \"")) {
                assertInvalid("{\"targets\":[" + TARGET.strip().replaceFirst(
                        "\"" + field + "\":\"[^\"]*\"", "\"" + field + "\":" + replacement) + "]}");
            }
        }
        for (String field : List.of("id", "component", "version")) {
            for (String replacement : List.of("null", "\" \"")) {
                assertInvalid("{\"software\":[" + SOFTWARE.strip().replaceFirst(
                        "\"" + field + "\":\"[^\"]*\"", "\"" + field + "\":" + replacement) + "]}");
            }
        }
        assertInvalid("{\"targets\":[{}]}");
        assertInvalid("{\"software\":[{}]}");
    }

    @Test
    void duplicateIdsAreRejectedWithinEachRequirementCollection() {
        assertInvalid("{\"targets\":[" + TARGET + "," + TARGET + "]}");
        assertInvalid("{\"software\":[" + SOFTWARE + "," + SOFTWARE + "]}");
        // Targets and software are independent namespaces.
        assertDoesNotThrow(() -> readProject("{\"targets\":[" + TARGET.replace("desktop", "chat")
                + "],\"software\":[" + SOFTWARE + "]}"));
    }

    @Test
    void floatingSoftwareVersionsAreNotPins() {
        for (String version : List.of("latest", "LATEST", "release", "stable", "1.+", "*",
                "[1,2)", "${revision}", "^1.2.3", "~1.2.3", "1.2 3")) {
            assertInvalid(withSoftware("version", version));
        }
    }

    @Test
    void exactSoftwareVersionsIncludingBuildMetadataAndLocalSnapshotsAreAccepted() throws Exception {
        for (String version : List.of("1.2.3", "1.2.3+build.4", "1.0.0-SNAPSHOT")) {
            assertEquals(version, readProject(withSoftware("version", version)).getDistribution()
                    .getSoftware().get(0).getVersion());
        }
    }

    @Test
    void softwarePathsMustBeRelativeWithoutParentTraversalOnEitherOs() throws Exception {
        for (String path : List.of("../escape", "runtime/../escape", "..\\escape", "runtime\\..\\escape",
                "/absolute", "C:\\absolute", "C:relative-drive", "\\\\server\\share", "", " ", "bad\u0000path")) {
            assertInvalid(withSoftware("path", path));
        }
        for (String path : List.of("runtime/chat", "runtime\\chat", "data/components/chat")) {
            assertEquals(path, readProject(withSoftware("path", path)).getDistribution()
                    .getSoftware().get(0).getPath());
        }
    }

    @Test
    void settersAllowIncompleteConstructionButValidationAndManifestAttachmentRejectIt() {
        KompileProjectDistribution distribution = new KompileProjectDistribution();
        KompileProjectDistribution.Target target = new KompileProjectDistribution.Target();
        KompileProjectDistribution.Software requirement = new KompileProjectDistribution.Software();
        distribution.setTargets(List.of(target));
        distribution.setSoftware(List.of(requirement));
        assertThrows(IllegalArgumentException.class, distribution::validate);
        target.setId("desktop");
        target.setOs("linux");
        target.setArchitecture("x86_64");
        target.setBackend("arbitrary-selection");
        requirement.setId("chat");
        requirement.setComponent("kompile-app-chat");
        requirement.setVersion("1.2.3");
        assertDoesNotThrow(distribution::validate);

        KompileProjectManifest project = new KompileProjectManifest();
        project.setDistribution(distribution);
        KompileProjectDistribution invalid = new KompileProjectDistribution();
        assertDoesNotThrow(() -> invalid.setDelivery("invalid"));
        assertThrows(IllegalArgumentException.class, () -> project.setDistribution(invalid));
        assertSame(distribution, project.getDistribution());
        project.setDistribution(null);
        assertNull(project.getDistribution());
        distribution.setSoftware(Arrays.asList(requirement, null));
        assertThrows(IllegalArgumentException.class, distribution::validate);
    }

    @Test
    void actualProjectStoreReaderRejectsBadDistribution(@TempDir Path root) throws Exception {
        Path manifest = root.resolve(KompileProjectStore.MANIFEST_FILE);
        Files.writeString(manifest, "{\"distribution\":{\"delivery\":\"thin\"}}");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new KompileProjectStore().load(root));
        assertTrue(error.getMessage().contains("distribution.delivery"));
        Files.writeString(manifest, "{\"distribution\":{}}");
        assertNotNull(new KompileProjectStore().load(root).getDistribution());
    }

    private KompileProjectManifest readProject(String distribution) throws IOException {
        return mapper.readValue("{\"distribution\":" + distribution + "}", KompileProjectManifest.class);
    }

    private void assertInvalid(String distribution) {
        assertThrows(IOException.class, () -> readProject(distribution), distribution);
    }

    private String withSoftware(String field, String value) {
        String encoded = mapper.valueToTree(value).toString();
        String requirement = SOFTWARE.strip();
        if ("version".equals(field)) {
            requirement = requirement.replace("\"version\":\"1.2.3\"", "\"version\":" + encoded);
        } else {
            requirement = requirement.substring(0, requirement.length() - 1) + ",\"" + field + "\":" + encoded + "}";
        }
        return "{\"software\":[" + requirement + "]}";
    }

    private void assertRoundTrip(KompileProjectManifest project) throws IOException {
        String encoded = mapper.writeValueAsString(project);
        KompileProjectManifest decoded = mapper.readValue(encoded, KompileProjectManifest.class);
        JsonNode expected = mapper.readTree(encoded);
        assertEquals(expected, mapper.readTree(mapper.writeValueAsString(decoded)));
    }
}
