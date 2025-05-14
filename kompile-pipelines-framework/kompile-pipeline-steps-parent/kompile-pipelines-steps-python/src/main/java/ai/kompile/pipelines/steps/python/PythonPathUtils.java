package ai.kompile.pipelines.steps.python;

import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import ai.kompile.pipelines.framework.core.utils.ProcessUtils;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.io.FileUtils;
import org.nd4j.shade.guava.collect.Streams; // Assuming this guava is appropriately shaded and available

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PythonPathUtils {

    public static final String FINDER_COMMAND = ProcessUtils.isWindows() ? "where" : "which";

    // Define the "profiles" directory relative to the Kompile home directory.
    // This makes "profiles" a sibling to "work", "data", "models" if those are also directly under KompileHome.
    private static final File PROFILES_DIR = new File(DirectoryFetcher.getKompileHomeDir(), "profiles");

    // Define the location of the registered installs JSON file within the "profiles" directory.
    // Example path: ~/.kompile/profiles/registered-installs.json
    private static final File registeredInstallDetailsLocation = new File(PROFILES_DIR, "registered-installs.json");

    public static List<PythonDetails> findPythonInstallations() {
        List<String> pythonInstallationPaths = findInstallationPaths(PythonType.PYTHON.name().toLowerCase());
        List<String> allPythonInstallations = Streams.concat(pythonInstallationPaths.stream(),
                filterRegisteredInstalls(PythonType.PYTHON).stream().map(RegisteredPythonInstall::path)).collect(Collectors.toList());

        return IntStream.range(0, allPythonInstallations.size())
                .mapToObj(index -> getPythonDetails(String.valueOf(index + 1), allPythonInstallations.get(index)))
                .collect(Collectors.toList());
    }

    public static PythonDetails getPythonDetails(String id, String pythonPath) {
        return new PythonDetails(id, pythonPath, getPythonVersion(pythonPath));
    }

    public static List<CondaDetails> findCondaInstallations() {
        List<String> condaInstallationPaths = findInstallationPaths(PythonType.CONDA.name().toLowerCase());
        List<String> allCondaInstalls = Streams.concat(condaInstallationPaths.stream(),
                filterRegisteredInstalls(PythonType.CONDA).stream().map(RegisteredPythonInstall::path)).collect(Collectors.toList());

        return IntStream.range(0, allCondaInstalls.size())
                .mapToObj(index -> getCondaDetails(String.valueOf(index + 1), allCondaInstalls.get(index)))
                .collect(Collectors.toList());
    }

    public static CondaDetails getCondaDetails(String id, String condaPath) {
        return new CondaDetails(id, condaPath, getCondaVersion(condaPath), findCondaEnvironments(condaPath));
    }

    public static List<VenvDetails> findVenvInstallations() {
        List<RegisteredPythonInstall> allVenvInstallations = filterRegisteredInstalls(PythonType.VENV);

        return IntStream.range(0, allVenvInstallations.size())
                .mapToObj(index -> getPythonDetails(String.valueOf(index + 1), getVenvPythonFile(allVenvInstallations.get(index).path()).getAbsolutePath()))
                .map(pythonDetails -> new VenvDetails(pythonDetails.id(), pythonDetails.path(), pythonDetails.version()))
                .collect(Collectors.toList());
    }

    public static String getPythonVersion(String pythonPath) {
        String pythonCommandOutput = ProcessUtils.runAndGetOutput(pythonPath, "-c", "import sys; print('Python ' + sys.version.split(' ')[0])");
        if(pythonCommandOutput.contains("Python ")) {
            return pythonCommandOutput.replace("Python ", "").trim(); // Added trim
        } else {
            throw new IllegalStateException(String.format("Path at '%s' is not a valid python executable.", pythonPath));
        }
    }

    public static String getCondaVersion(String condaPath) {
        String condaCommandOutput = ProcessUtils.runAndGetOutput(condaPath, "--version");
        if(condaCommandOutput.contains("conda ")) {
            return condaCommandOutput.replace("conda ", "").trim(); // Added trim
        } else {
            throw new IllegalStateException(String.format("Path at '%s' is not a valid conda executable.", condaPath));
        }
    }

    public static String getVenvVersion(String venvPath) {
        File venvPythonPath = getVenvPythonFile(venvPath);
        if(!venvPythonPath.exists()) {
            throw new IllegalStateException(String.format("Unable to find python path associated with the virtual environment at '%s'. " +
                    "Please ensure the specified venv path at '%s' is a valid python virtual environment.", venvPythonPath.getAbsoluteFile(), new File(venvPath).getAbsoluteFile()));
        } else {
            return getPythonVersion(venvPythonPath.getAbsolutePath());
        }
    }

    public static File getVenvPythonFile(String venvPath) {
        return Paths.get(venvPath, ProcessUtils.isWindows() ? "Scripts" : "bin", ProcessUtils.isWindows() ? "python.exe" : "python").toFile();
    }

    public static String getPythonPathFromRoot(String rootPath) {
        return (ProcessUtils.isWindows() ?
                Paths.get(rootPath, "python.exe") :
                Paths.get(rootPath, "bin", "python"))
                .toFile().getAbsolutePath();
    }

    public static List<String> findInstallationPaths(String type) {
        String commandOutput = ProcessUtils.runAndGetOutput(FINDER_COMMAND, type);
        if (commandOutput == null || commandOutput.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(commandOutput.split(System.lineSeparator()))
                .map(String::trim) // Trim each path
                .filter(s -> !s.isEmpty()) // Filter out empty lines
                .collect(Collectors.toList());
    }

    public static List<PythonDetails> findCondaEnvironments(String condaPath) {
        // It's good practice to ensure condaPath is a valid executable before proceeding
        File condaFile = new File(condaPath);
        if (!condaFile.exists() || !condaFile.canExecute()) {
            return new ArrayList<>();
        }

        String condaInfoOutput = ProcessUtils.runAndGetOutput(condaPath, "info", "-e");
        if (condaInfoOutput == null || condaInfoOutput.trim().isEmpty()) {
            return new ArrayList<>();
        }

        return Arrays.stream(
                        condaInfoOutput
                                .replace("*", " ") // Remove active environment indicator for parsing
                                .replace("# conda environments:", "") // Remove header
                                .replace("#", "") // Remove any other comment lines if they start with # only
                                .trim()
                                .split(System.lineSeparator()))
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#")) // Ensure line isn't empty or a comment
                .map(envInfo -> {
                    String[] envInfoSplits = envInfo.split("\\s+", 2);
                    if (envInfoSplits.length < 2) {
                        return null;
                    }
                    String envName = envInfoSplits[0].trim();
                    String envPath = envInfoSplits[1].trim();
                    try {
                        String resolvedPythonPath = getPythonPathFromRoot(envPath);
                        // Ensure resolvedPythonPath is valid before getting version
                        File pythonExe = new File(resolvedPythonPath);
                        if (pythonExe.exists() && pythonExe.canExecute()) {
                            return new PythonDetails(envName, resolvedPythonPath, getPythonVersion(resolvedPythonPath));
                        } else {
                            return null;
                        }
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull) // Filter out nulls from parsing errors
                .collect(Collectors.toList());
    }

    public static void registerInstallation(PythonType pythonType, String path) {
        List<RegisteredPythonInstall> registeredPythonInstalls = getRegisteredPythonInstalls();
        String absolutePath = new File(path).getAbsolutePath(); // Use absolute path for consistency
        String version = "";

        try {
            switch (pythonType) {
                case PYTHON:
                    version = getPythonVersion(absolutePath);
                    break;
                case CONDA:
                    version = getCondaVersion(absolutePath);
                    break;
                case VENV:
                    version = getVenvVersion(absolutePath); // venvPath here is the root of venv
                    break;
                default:
                    System.err.format("Unknown PythonType: %s%n", pythonType);
                    return;
            }
            registeredPythonInstalls.add(new RegisteredPythonInstall(pythonType, absolutePath, version));
            saveRegisteredPythonInstalls(registeredPythonInstalls);
            System.out.format("Registered installation of type: '%s' from location: '%s' (Version: %s)%n", pythonType.name(), absolutePath, version);
        } catch (Exception e) {
            System.err.format("Failed to register installation of type '%s' from path '%s': %s%n", pythonType, absolutePath, e.getMessage());
        }
    }

    public static List<RegisteredPythonInstall> getRegisteredPythonInstalls() {
        if (!registeredInstallDetailsLocation.exists()) {
            return new ArrayList<>();
        }
        try {
            // Ensure the parent directory exists before attempting to read the file,
            // though for reading, this is less critical than for writing.
            // FileUtils.readFileToString handles FileNotFoundException gracefully.
            String jsonContent = FileUtils.readFileToString(registeredInstallDetailsLocation, StandardCharsets.UTF_8);
            if (jsonContent.trim().isEmpty()) {
                return new ArrayList<>();
            }
            RegisteredPythonInstalls installs = ObjectMappers.getJsonMapper().readValue(jsonContent, RegisteredPythonInstalls.class);
            return installs != null ? installs.registeredPythonInstalls() : new ArrayList<>();
        } catch (FileNotFoundException e) {
            return new ArrayList<>();
        } catch (IOException e) {
            // Depending on policy, could exit or return empty list. Returning empty is safer.
            // System.exit(1);
            return new ArrayList<>();
        }
    }

    public static List<RegisteredPythonInstall> filterRegisteredInstalls(PythonType pythonType) {
        return getRegisteredPythonInstalls().stream()
                .filter(registeredPythonInstall -> registeredPythonInstall.pythonType().equals(pythonType))
                .collect(Collectors.toList());
    }

    public static void saveRegisteredPythonInstalls(List<RegisteredPythonInstall> registeredPythonInstalls) {
        try {
            // Ensure the parent directory (PROFILES_DIR) exists before writing.
            // FileUtils.writeStringToFile will create parent directories.
            FileUtils.forceMkdirParent(registeredInstallDetailsLocation); // More explicit directory creation
            String jsonContent = ObjectMappers.getJsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(new RegisteredPythonInstalls(registeredPythonInstalls));
            FileUtils.writeStringToFile(registeredInstallDetailsLocation, jsonContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Depending on policy, could exit or throw runtime exception.
        }
    }

    public enum PythonType {
        PYTHON, CONDA, VENV
    }
}