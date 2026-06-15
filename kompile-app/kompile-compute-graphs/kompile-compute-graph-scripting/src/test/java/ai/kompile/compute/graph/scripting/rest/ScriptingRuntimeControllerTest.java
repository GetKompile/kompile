package ai.kompile.compute.graph.scripting.rest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ScriptingRuntimeController.
 * Tests Python4J pip management and JavaScript GraalVM runtime info endpoints.
 *
 * GraalVM polyglot is always on the test classpath, so JS tests run fully.
 * Python4J requires native cpython libs — tests that invoke pip methods
 * are skipped when the native lib isn't available (CI without cpython).
 */
class ScriptingRuntimeControllerTest {

    private ScriptingRuntimeController controller;

    private static boolean python4jNativeAvailable;

    static {
        try {
            Class.forName("org.nd4j.python4j.PythonProcess");
            python4jNativeAvailable = true;
        } catch (Throwable e) {
            python4jNativeAvailable = false;
        }
    }

    @BeforeEach
    void setUp() {
        controller = new ScriptingRuntimeController();
    }

    // ==================== JavaScript Runtime Tests ====================

    @Test
    @SuppressWarnings("unchecked")
    void testJavaScriptRuntime_available() {
        ResponseEntity<Map<String, Object>> response = controller.getJavaScriptRuntime();

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("GraalVM Polyglot", body.get("backend"));
        assertTrue((boolean) body.get("available"));
        assertNotNull(body.get("engineVersion"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testJavaScriptRuntime_reportsLanguages() {
        ResponseEntity<Map<String, Object>> response = controller.getJavaScriptRuntime();
        Map<String, Object> body = response.getBody();

        List<Map<String, Object>> languages = (List<Map<String, Object>>) body.get("languages");
        assertNotNull(languages);
        assertFalse(languages.isEmpty());

        // At minimum, "js" should be in the language list
        boolean hasJs = languages.stream()
                .anyMatch(lang -> "js".equals(lang.get("id")));
        assertTrue(hasJs, "Expected 'js' language in GraalVM engine");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testJavaScriptRuntime_languageHasRequiredFields() {
        ResponseEntity<Map<String, Object>> response = controller.getJavaScriptRuntime();
        Map<String, Object> body = response.getBody();
        List<Map<String, Object>> languages = (List<Map<String, Object>>) body.get("languages");

        for (Map<String, Object> lang : languages) {
            assertNotNull(lang.get("id"), "language missing 'id'");
            assertNotNull(lang.get("name"), "language missing 'name' for " + lang.get("id"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void testJavaScriptRuntime_engineVersionIsString() {
        ResponseEntity<Map<String, Object>> response = controller.getJavaScriptRuntime();
        Object version = response.getBody().get("engineVersion");
        assertInstanceOf(String.class, version);
        assertFalse(((String) version).isBlank());
    }

    // ==================== Python Runtime Tests ====================

    @Test
    void testPythonRuntime_reportsBackendName() {
        ResponseEntity<Map<String, Object>> response = controller.getPythonRuntime();

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("Python4J (CPython via JavaCPP)", body.get("backend"));
        // available is true only if native libs loaded successfully
        assertNotNull(body.get("available"));
    }

    @Test
    void testPythonRuntime_availableMatchesNativeStatus() {
        ResponseEntity<Map<String, Object>> response = controller.getPythonRuntime();
        assertEquals(python4jNativeAvailable, response.getBody().get("available"));
    }

    // ==================== Python Package Query Tests ====================

    @Test
    void testGetPackageInfo_nonexistentPackage_whenNativeAvailable() {
        if (!python4jNativeAvailable) {
            // Without native libs, the controller returns 400 "not available"
            ResponseEntity<Map<String, Object>> response =
                    controller.getPackageInfo("nonexistent_pkg");
            assertEquals(400, response.getStatusCode().value());
            return;
        }
        ResponseEntity<Map<String, Object>> response =
                controller.getPackageInfo("this_package_definitely_does_not_exist_12345");
        assertEquals(200, response.getStatusCode().value());
        assertFalse((boolean) response.getBody().get("installed"));
    }

    @Test
    void testGetPackageInfo_nativeUnavailable_returns400() {
        if (python4jNativeAvailable) return; // skip when native IS available
        ResponseEntity<Map<String, Object>> response =
                controller.getPackageInfo("anypackage");
        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().get("error").toString().contains("not available"));
    }

    // ==================== Python Install/Uninstall Validation Tests ====================
    // These tests validate input checking BEFORE any Python4J call happens.

    @Test
    void testInstallPackage_missingPackageName() {
        ResponseEntity<Map<String, Object>> response =
                controller.installPackage(Map.of());
        // If python not available, returns 400 with "not available" first
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void testInstallPackage_blankPackageName() {
        ResponseEntity<Map<String, Object>> response =
                controller.installPackage(Map.of("packageName", "   "));
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void testUninstallPackage_missingPackageName() {
        ResponseEntity<Map<String, Object>> response =
                controller.uninstallPackage(Map.of());
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void testUninstallPackage_blankPackageName() {
        ResponseEntity<Map<String, Object>> response =
                controller.uninstallPackage(Map.of("packageName", ""));
        assertEquals(400, response.getStatusCode().value());
    }

    // ==================== Requirements Install Validation Tests ====================

    @Test
    void testInstallFromRequirements_missingPath() {
        ResponseEntity<Map<String, Object>> response =
                controller.installFromRequirements(Map.of());
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void testInstallFromRequirements_blankPath() {
        ResponseEntity<Map<String, Object>> response =
                controller.installFromRequirements(Map.of("path", "  "));
        assertEquals(400, response.getStatusCode().value());
    }

    // ==================== Response Structure Tests ====================

    @Test
    void testJavaScriptRuntime_responseHasRequiredKeys() {
        ResponseEntity<Map<String, Object>> response = controller.getJavaScriptRuntime();
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("backend"));
        assertTrue(body.containsKey("available"));
    }

    @Test
    void testPythonRuntime_responseHasRequiredKeys() {
        ResponseEntity<Map<String, Object>> response = controller.getPythonRuntime();
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("backend"));
        assertTrue(body.containsKey("available"));
    }

    @Test
    void testInstallPackage_validationOrderCorrect() {
        // When python is unavailable, the "not available" error takes precedence
        // When python IS available but packageName is missing, that error takes precedence
        ResponseEntity<Map<String, Object>> response =
                controller.installPackage(Map.of());
        assertEquals(400, response.getStatusCode().value());
        String error = response.getBody().get("error").toString();
        // Either "not available" or "packageName is required" — both are valid 400s
        assertTrue(error.contains("not available") || error.contains("packageName is required"));
    }

    @Test
    void testUninstallPackage_validationOrderCorrect() {
        ResponseEntity<Map<String, Object>> response =
                controller.uninstallPackage(Map.of());
        assertEquals(400, response.getStatusCode().value());
        String error = response.getBody().get("error").toString();
        assertTrue(error.contains("not available") || error.contains("packageName is required"));
    }

    @Test
    void testInstallFromRequirements_validationOrderCorrect() {
        ResponseEntity<Map<String, Object>> response =
                controller.installFromRequirements(Map.of());
        assertEquals(400, response.getStatusCode().value());
        String error = response.getBody().get("error").toString();
        assertTrue(error.contains("not available") || error.contains("path"));
    }
}
