package ai.kompile.pipelines.steps.python;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;


@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DirectoryFetcher {

    // Kompile-specific base directory name under user.home
    private static final String KOMPILE_HOME_DIR_NAME = ".kompile";

    // --- Configuration Keys and Default Names ---

    // Work Directory
    private static final String ENV_KEY_WORK_DIR = "KOMPILE_WORK_DIR";
    private static final String PROP_KEY_WORK_DIR = "kompile.work.dir";
    private static final String DEFAULT_WORK_DIR_NAME = "work"; // Relative to KOMPILE_HOME_DIR_NAME

    // Data Directory
    private static final String ENV_KEY_DATA_DIR = "KOMPILE_DATA_DIR";
    private static final String PROP_KEY_DATA_DIR = "kompile.data.dir";
    private static final String DEFAULT_DATA_DIR_NAME = "data"; // Relative to KOMPILE_HOME_DIR_NAME

    // Model Directory
    private static final String ENV_KEY_MODEL_DIR = "KOMPILE_MODEL_DIR";
    private static final String PROP_KEY_MODEL_DIR = "kompile.model.dir";
    private static final String DEFAULT_MODEL_DIR_NAME = "models"; // Relative to KOMPILE_HOME_DIR_NAME

    /**
     * Fetches a configuration value by checking an environment variable first,
     * then a system property.
     *
     * @param envKey  The key for the environment variable.
     * @param propKey The key for the system property.
     * @return The found value, or null if not found in either.
     */
    private static String getValueFromEnvOrProperty(String envKey, String propKey) {
        String value = System.getenv(envKey);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        value = System.getProperty(propKey);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        return null;
    }

    /**
     * Gets the root directory for Kompile settings and default subdirectories (e.g., ~/.kompile).
     *
     * @return The Kompile root directory File object.
     */
    public static File getKompileHomeDir() {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isEmpty()) {
            // Fallback to a ".kompile" directory in the current working directory if user.home is not available
            return Paths.get(".", KOMPILE_HOME_DIR_NAME).toAbsolutePath().toFile();
        }
        return new File(userHome, KOMPILE_HOME_DIR_NAME);
    }

    /**
     * Gets the absolute path to the Kompile root directory.
     *
     * @return Absolute path string.
     */
    public static String getKompileHomePath() {
        return getKompileHomeDir().getAbsolutePath();
    }

    /**
     * Gets a File object representing a path relative to the Kompile root directory.
     *
     * @param relativePath The path relative to the Kompile root.
     * @return The absolute File object.
     */
    public static File getFileRelativeToKompileHome(String relativePath) {
        return new File(getKompileHomeDir(), relativePath);
    }

    /**
     * Gets a File object representing a file within the configured working directory.
     *
     * @param fileName The name of the file.
     * @return File object within the work directory.
     */
    public static File getFileInWorkDir(String fileName) {
        return new File(getWorkDir(), fileName);
    }

    /**
     * Retrieves the working directory path.
     * Priority: Environment Variable -> System Property -> Default (KOMPILE_HOME_DIR/work).
     * Creates the directory if it doesn't exist.
     *
     * @return The working directory File object.
     * @throws IllegalStateException if the path exists but is not a directory, or if creation fails.
     */
    public static File getWorkDir() {
        String workDirStr = getValueFromEnvOrProperty(ENV_KEY_WORK_DIR, PROP_KEY_WORK_DIR);

        File workDir;
        if (workDirStr == null || workDirStr.isEmpty() || "null".equalsIgnoreCase(workDirStr)) {
            workDir = new File(getKompileHomeDir(), DEFAULT_WORK_DIR_NAME);
        } else {
            workDir = new File(workDirStr);
        }

        return ensureDirectoryExists(workDir, "Working directory");
    }

    /**
     * Retrieves the data directory path.
     * Priority: Environment Variable -> System Property -> Default (KOMPILE_HOME_DIR/data).
     * Creates the directory if it doesn't exist.
     *
     * @return The data directory File object.
     * @throws IllegalStateException if the path exists but is not a directory, or if creation fails.
     */
    public static File getDataDir() {
        String dataDirStr = getValueFromEnvOrProperty(ENV_KEY_DATA_DIR, PROP_KEY_DATA_DIR);

        File dataDir;
        if (dataDirStr == null || dataDirStr.isEmpty() || "null".equalsIgnoreCase(dataDirStr)) {
            dataDir = new File(getKompileHomeDir(), DEFAULT_DATA_DIR_NAME);
        } else {
            dataDir = new File(dataDirStr);
        }

        return ensureDirectoryExists(dataDir, "Data directory");
    }

    /**
     * Retrieves the model directory path.
     * Priority: Environment Variable -> System Property -> Default (KOMPILE_HOME_DIR/models).
     * Creates the directory if it doesn't exist.
     *
     * @return The model directory File object.
     * @throws IllegalStateException if the path exists but is not a directory, or if creation fails.
     */
    public static File getModelDir() {
        String modelDirStr = getValueFromEnvOrProperty(ENV_KEY_MODEL_DIR, PROP_KEY_MODEL_DIR);

        File modelDir;
        if (modelDirStr == null || modelDirStr.isEmpty() || "null".equalsIgnoreCase(modelDirStr)) {
            modelDir = new File(getKompileHomeDir(), DEFAULT_MODEL_DIR_NAME);
        } else {
            modelDir = new File(modelDirStr);
        }

        return ensureDirectoryExists(modelDir, "Model directory");
    }

    /**
     * Ensures that the given directory path exists and is a directory.
     * If it does not exist, it attempts to create it.
     *
     * @param dirPath        The File object representing the directory.
     * @param directoryType A descriptive name for the directory (e.g., "Working directory") for logging.
     * @return The File object if it's a valid directory.
     * @throws IllegalStateException if the path exists but is not a directory, or if creation fails.
     */
    private static File ensureDirectoryExists(File dirPath, String directoryType) {
        if (!dirPath.exists()) {
            try {
                Files.createDirectories(dirPath.toPath());
            } catch (IOException e) {
                String errorMessage = String.format("Unable to create %s: %s", directoryType.toLowerCase(), dirPath.getAbsolutePath());
                throw new IllegalStateException(errorMessage, e);
            }
        } else if (!dirPath.isDirectory()) {
            String errorMessage = String.format("%s path %s is a file, not a directory. Please specify a valid directory.",
                    directoryType, dirPath.getAbsolutePath());
            throw new IllegalStateException(errorMessage);
        }
        return dirPath;
    }


    /**
     * Ensures that a child path is indeed located within a given base path.
     * This is a security measure to prevent path traversal issues.
     *
     * @param childPath The child file or directory.
     * @param basePath  The base directory.
     * @throws IOException if the child path is not within the base path, or if canonical paths cannot be resolved.
     */
    public static void ensureChildPathIsInBasePath(File childPath, File basePath) throws IOException {
        String canonicalBasePath = basePath.getCanonicalPath();
        String canonicalChildPath = childPath.getCanonicalPath();

        if (!canonicalChildPath.startsWith(canonicalBasePath)) {
            throw new IOException(String.format("Path %s is not a child of base path %s. Path traversal attempt suspected.",
                    childPath.getAbsolutePath(), basePath.getAbsolutePath()));
        }
    }
}