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

package ai.kompile.cli.common.util;

import ai.kompile.cli.common.KompileHome;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Environment and path resolution utilities for Kompile CLI tools.
 */
public class EnvironmentUtils {

    public static final String KOMPILE_PREFIX = "KOMPILE_PREFIX";

    public static Pattern ENV_REGEX = Pattern.compile("\\$\\{env\\.([A-Za-z_\\.0-9])+\\}");
    public static Pattern PROP_REGEX = Pattern.compile("\\$\\{([A-Za-z\\._0-9])+\\}");

    /**
     * Searches the PATH for a given executable.
     *
     * @param targetFile the target file to find
     * @return the first target file matching the given name or null
     */
    public static File executableOnPath(String targetFile) {
        return Arrays.stream(System.getenv("PATH").split(File.pathSeparator))
                .map(File::new)
                .filter(File::exists)
                .filter(input -> input.listFiles() != null && input.listFiles().length > 0)
                .filter(input -> Arrays.asList(input.list()).contains(targetFile))
                .findFirst().map(input -> new File(input, targetFile)).orElse(null);
    }

    /**
     * Returns the default kompile python path.
     */
    public static String defaultKompilePythonPath() {
        return defaultFolderFromPath("kompile-python");
    }

    /**
     * Returns the default kompile C library path.
     */
    public static String defaultKompileCPath() {
        return defaultFolderFromPath("kompile-c-library");
    }

    public static String defaultNativeImageFilesPath() {
        return defaultFolderFromPath("native-image");
    }

    protected static String defaultFolderFromPath(String path) {
        if (isDocker()) {
            return new File("/kompile/" + path).getAbsolutePath();
        } else if (System.getenv().containsKey(KOMPILE_PREFIX)) {
            return new File(KOMPILE_PREFIX, path).getAbsolutePath();
        }
        return null;
    }

    /**
     * Returns the default python executable path.
     */
    public static String defaultPythonExecutable() {
        File f = KompileHome.pythonDirectory();
        File pythonExec = new File(f, "bin/python");
        if (pythonExec.exists())
            return pythonExec.getAbsolutePath();
        File pythonExecPathSearch = executableOnPath("python");
        if (pythonExecPathSearch != null)
            return pythonExecPathSearch.getAbsolutePath();
        return null;
    }

    /**
     * Returns true if running in a docker container.
     */
    public static boolean isDocker() {
        File f = new File("/.dockerenv");
        return f.exists();
    }

    /**
     * Returns the default maven home directory.
     */
    public static File defaultMavenHome() {
        File mvn = KompileHome.mavenDirectory();
        if (mvn.exists())
            return mvn;

        if (System.getenv().containsKey("M2_HOME")) {
            mvn = new File(System.getenv("M2_HOME"));
        }

        if (mvn.exists())
            return mvn;

        File executablePath = executableOnPath("mvn");
        if (executablePath != null)
            return executablePath.getParentFile().getParentFile();

        return null;
    }

    /**
     * Resolve environment variables in a string matching {@code ${env.VAR_NAME}}.
     */
    public static String resolveEnvPropertyValue(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = ENV_REGEX.matcher(value);
        List<String> allMatches = new ArrayList<>();
        while (matcher.find()) {
            allMatches.add(matcher.group());
        }

        for (String match : allMatches) {
            String envKey = match.replace("${", "").replace("}", "");
            String[] keyValueSplit = envKey.split("\\.");
            String value2 = System.getenv(keyValueSplit[1]);
            if (value2 == null) {
                throw new IllegalStateException("No environment variable " + keyValueSplit[1] + " found!");
            }
            value = value.replace(match, value2);
        }

        return value;
    }

    /**
     * Resolve system properties in a string matching {@code ${PROPERTY_NAME}}.
     */
    public static String resolvePropertyValue(String value) {
        if (value == null)
            return null;
        Matcher matcher = PROP_REGEX.matcher(value);
        List<String> allMatches = new ArrayList<>();
        while (matcher.find()) {
            allMatches.add(matcher.group());
        }

        for (String match : allMatches) {
            String envKey = match.replace("${", "").replace("}", "");
            if (envKey.contains("env.")) {
                String value2 = System.getenv(envKey.replace("env.", ""));
                if (value2 == null) {
                    throw new IllegalStateException("No system property " + envKey + " found!");
                }
                value = value.replace(match, value2);
            } else {
                String value2 = System.getProperty(envKey);
                if (value2 == null) {
                    throw new IllegalStateException("No system property " + envKey + " found!");
                }
                value = value.replace(match, value2);
            }
        }

        return value;
    }
}
