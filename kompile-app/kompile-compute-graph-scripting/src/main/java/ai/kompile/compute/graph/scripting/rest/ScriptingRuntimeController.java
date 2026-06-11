package ai.kompile.compute.graph.scripting.rest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API for managing scripting runtime environments.
 * Provides Python pip package management (via Python4J) and
 * JavaScript runtime information (via GraalVM Polyglot).
 */
@Slf4j
@RestController
@RequestMapping("/api/scripting")
public class ScriptingRuntimeController {

    // ==================== Python Package Management ====================

    /**
     * List installed Python packages or check a specific one.
     */
    @GetMapping("/python/packages/{packageName}")
    public ResponseEntity<Map<String, Object>> getPackageInfo(@PathVariable String packageName) {
        if (!isPython4jAvailable()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Python4J is not available on the classpath"));
        }
        try {
            boolean installed = invokePipIsInstalled(packageName);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("packageName", packageName);
            info.put("installed", installed);
            if (installed) {
                try {
                    String version = invokePipGetVersion(packageName);
                    info.put("version", version);
                } catch (Exception e) {
                    info.put("version", "unknown");
                }
            }
            return ResponseEntity.ok(info);
        } catch (Exception e) {
            log.error("Failed to query Python package '{}': {}", packageName, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to query package: " + e.getMessage()));
        }
    }

    /**
     * Install a Python package via pip.
     */
    @PostMapping("/python/packages/install")
    public ResponseEntity<Map<String, Object>> installPackage(@RequestBody Map<String, String> request) {
        if (!isPython4jAvailable()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Python4J is not available on the classpath"));
        }
        String packageName = request.get("packageName");
        String version = request.get("version");
        if (packageName == null || packageName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "packageName is required"));
        }
        try {
            if (version != null && !version.isBlank()) {
                invokePipInstall(packageName, version);
            } else {
                invokePipInstall(packageName);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("packageName", packageName);
            result.put("action", "install");
            result.put("success", true);
            // Verify installation
            try {
                String installedVersion = invokePipGetVersion(packageName);
                result.put("installedVersion", installedVersion);
            } catch (Exception ignored) {
                // best-effort version query
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to install Python package '{}': {}", packageName, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to install package: " + e.getMessage(),
                    "packageName", packageName,
                    "success", false));
        }
    }

    /**
     * Uninstall a Python package via pip.
     */
    @PostMapping("/python/packages/uninstall")
    public ResponseEntity<Map<String, Object>> uninstallPackage(@RequestBody Map<String, String> request) {
        if (!isPython4jAvailable()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Python4J is not available on the classpath"));
        }
        String packageName = request.get("packageName");
        if (packageName == null || packageName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "packageName is required"));
        }
        try {
            invokePipUninstall(packageName);
            return ResponseEntity.ok(Map.of(
                    "packageName", packageName,
                    "action", "uninstall",
                    "success", true));
        } catch (Exception e) {
            log.error("Failed to uninstall Python package '{}': {}", packageName, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to uninstall package: " + e.getMessage(),
                    "packageName", packageName,
                    "success", false));
        }
    }

    /**
     * Install packages from a requirements.txt file.
     */
    @PostMapping("/python/packages/install-requirements")
    public ResponseEntity<Map<String, Object>> installFromRequirements(@RequestBody Map<String, String> request) {
        if (!isPython4jAvailable()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Python4J is not available on the classpath"));
        }
        String path = request.get("path");
        if (path == null || path.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "path to requirements.txt is required"));
        }
        try {
            invokePipInstallFromRequirements(path);
            return ResponseEntity.ok(Map.of(
                    "path", path,
                    "action", "install-requirements",
                    "success", true));
        } catch (Exception e) {
            log.error("Failed to install from requirements '{}': {}", path, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to install from requirements: " + e.getMessage(),
                    "success", false));
        }
    }

    /**
     * Get Python runtime info.
     */
    @GetMapping("/python/runtime")
    public ResponseEntity<Map<String, Object>> getPythonRuntime() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("backend", "Python4J (CPython via JavaCPP)");
        info.put("available", isPython4jAvailable());
        if (isPython4jAvailable()) {
            try {
                String version = invokePythonVersion();
                info.put("pythonVersion", version);
            } catch (Exception e) {
                info.put("pythonVersion", "unknown");
                info.put("error", e.getMessage());
            }
        }
        return ResponseEntity.ok(info);
    }

    // ==================== JavaScript Runtime Info ====================

    /**
     * Get JavaScript runtime information.
     */
    @GetMapping("/javascript/runtime")
    public ResponseEntity<Map<String, Object>> getJavaScriptRuntime() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("backend", "GraalVM Polyglot");
        boolean available = isGraalVmAvailable();
        info.put("available", available);
        if (available) {
            try {
                info.putAll(getGraalVmDetails());
            } catch (Exception e) {
                info.put("error", e.getMessage());
            }
        }
        return ResponseEntity.ok(info);
    }

    // ==================== Python4J Reflective Wrappers ====================
    // All Python4J calls go through reflection so this controller compiles even without
    // python4j-core on the classpath (the module is always present in kompile-compute-graph-scripting,
    // but this keeps the REST layer safe if the class can't load at runtime).

    private volatile Boolean python4jAvailable;

    private boolean isPython4jAvailable() {
        if (python4jAvailable != null) return python4jAvailable;
        try {
            // Use initialize=true to verify the native library can actually load
            Class.forName("org.nd4j.python4j.PythonProcess");
            python4jAvailable = true;
        } catch (Throwable e) {
            // ClassNotFoundException, NoClassDefFoundError, UnsatisfiedLinkError, ExceptionInInitializerError
            log.debug("Python4J not available: {}", e.getMessage());
            python4jAvailable = false;
        }
        return python4jAvailable;
    }

    private boolean invokePipIsInstalled(String packageName) throws Exception {
        Class<?> cls = Class.forName("org.nd4j.python4j.PythonProcess");
        return (boolean) cls.getMethod("isPackageInstalled", String.class).invoke(null, packageName);
    }

    private String invokePipGetVersion(String packageName) throws Exception {
        Class<?> cls = Class.forName("org.nd4j.python4j.PythonProcess");
        return (String) cls.getMethod("getPackageVersion", String.class).invoke(null, packageName);
    }

    private void invokePipInstall(String packageName) throws Exception {
        Class<?> cls = Class.forName("org.nd4j.python4j.PythonProcess");
        cls.getMethod("pipInstall", String.class).invoke(null, packageName);
    }

    private void invokePipInstall(String packageName, String version) throws Exception {
        Class<?> cls = Class.forName("org.nd4j.python4j.PythonProcess");
        cls.getMethod("pipInstall", String.class, String.class).invoke(null, packageName, version);
    }

    private void invokePipUninstall(String packageName) throws Exception {
        Class<?> cls = Class.forName("org.nd4j.python4j.PythonProcess");
        cls.getMethod("pipUninstall", String.class).invoke(null, packageName);
    }

    private void invokePipInstallFromRequirements(String path) throws Exception {
        Class<?> cls = Class.forName("org.nd4j.python4j.PythonProcess");
        cls.getMethod("pipInstallFromRequirementsTxt", String.class).invoke(null, path);
    }

    private String invokePythonVersion() throws Exception {
        Class<?> cls = Class.forName("org.nd4j.python4j.PythonProcess");
        String output = (String) cls.getMethod("runAndReturn", String[].class)
                .invoke(null, (Object) new String[]{"--version"});
        return output != null ? output.trim() : "unknown";
    }

    // ==================== GraalVM Reflective Wrappers ====================

    private boolean isGraalVmAvailable() {
        try {
            Class.forName("org.graalvm.polyglot.Engine", false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getGraalVmDetails() throws Exception {
        Class<?> engineClass = Class.forName("org.graalvm.polyglot.Engine");
        Object engine = engineClass.getMethod("create").invoke(null);
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("engineVersion", engineClass.getMethod("getVersion").invoke(engine));

            // Get available languages
            Map<String, ?> languages = (Map<String, ?>) engineClass.getMethod("getLanguages").invoke(engine);
            List<Map<String, Object>> langList = new ArrayList<>();
            for (Map.Entry<String, ?> entry : languages.entrySet()) {
                Map<String, Object> langInfo = new LinkedHashMap<>();
                langInfo.put("id", entry.getKey());
                Object langObj = entry.getValue();
                Class<?> langClass = langObj.getClass();
                try {
                    langInfo.put("name", langClass.getMethod("getName").invoke(langObj));
                    langInfo.put("version", langClass.getMethod("getVersion").invoke(langObj));
                    langInfo.put("defaultMimeType", langClass.getMethod("getDefaultMimeType").invoke(langObj));
                } catch (Exception ignored) {
                    // some methods may not exist on all versions
                }
                langList.add(langInfo);
            }
            details.put("languages", langList);
            return details;
        } finally {
            engineClass.getMethod("close").invoke(engine);
        }
    }
}
