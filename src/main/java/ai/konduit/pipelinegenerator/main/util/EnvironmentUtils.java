/*
 * Copyright (c) 2022 Konduit K.K.
 *
 *     This program and the accompanying materials are made available under the
 *     terms of the Apache License, Version 2.0 which is available at
 *     https://www.apache.org/licenses/LICENSE-2.0.
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *     WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *     License for the specific language governing permissions and limitations
 *     under the License.
 *
 *     SPDX-License-Identifier: Apache-2.0
 */

package ai.konduit.pipelinegenerator.main.util;

import ai.konduit.pipelinegenerator.main.Info;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EnvironmentUtils {


    public static final String KOMPILE_PREFIX = "KOMPILE_PREFIX";

    public static Pattern ENV_REGEX = Pattern.compile("\\$\\{env\\.([A-Za-z_\\.0-9])+\\}");
    public static Pattern PROP_REGEX = Pattern.compile("\\$\\{([A-Za-z\\._0-9])+\\}");

    /**
     * Searches the path for a given executable.
     * First splits the PATH environment variable by
     * the file separator for the specific platform
     * then searches for any file on the path with the target name.
     *
     * Returns the first value found in this search or null.
     * @param targetFile the target file to find.
     * @return the first target file matching the given name or null
     */
    public static File executableOnPath(String targetFile) {
        return Arrays.stream(System.getenv("PATH").split(File.pathSeparator))
                .map(input -> new File(input))
                .filter(input -> input.exists())
                .filter(input -> input.listFiles() != null && input.listFiles().length > 0)
                .filter(input -> Arrays.asList(input.list())
                        .contains(targetFile))
                .findFirst().map(input -> new File(input,targetFile)).orElse(null);
    }


    /**
     * Returns the default kompile python path.
     * This will either be at: /kompile/kompile-python or
     * at $KOMPILE_PREFIX/kompile-python
     *
     * @return
     */
    public static String defaultKompilePythonPath() {
        return defaultFolderFromPath("kompile-python");
    }


    /**
     * Returns the default kompile python path.
     * This will either be at: /kompile/kompile-c-library or
     * at $KOMPILE_PREFIX/kompile-c-library
     *
     * @return
     */
    public static String defaultKompileCPath() {
        return defaultFolderFromPath("kompile-c-library");
    }



    public static String defaultNativeImageFilesPath() {
        return defaultFolderFromPath("native-image");
    }

    /**
     * Returns the default folder from a given path.
     * Checks either /kompile if running in a container
     * or $KOMPILE_PREFIX otherwise.
     * @param path the path to check for
     * @return
     */
    protected static String defaultFolderFromPath(String path) {
        if(isDocker()) {
            return new File("/kompile/" + path).getAbsolutePath();
        } else if(System.getenv().containsKey(KOMPILE_PREFIX)) {
            return new File(KOMPILE_PREFIX,path).getAbsolutePath();
        }
        return null;
    }


    /**
     * Returns the default python path executable.
     * First it searches for the default kompile install
     * returned by {@link Info#pythonDirectory()}
     * and if so uses that. Otherwise
     * it attempts to search for a python executable
     * on the path and uses that.
     * @return
     */
    public static String defaultPythonExecutable() {
        File f = Info.pythonDirectory();
        File pythonExec = new File(f,"bin/python");
        if(pythonExec.exists())
            return pythonExec.getAbsolutePath();
        File pythonExecPathSearch = executableOnPath("python");
        if(pythonExecPathSearch != null)
            return pythonExecPathSearch.getAbsolutePath();
        return null;
    }

    /**
     * Returns true if running in a docker container
     * @return
     */
    public static boolean isDocker() {
        File f = new File("/.dockerenv");
        return f.exists();
    }

    /**
     * Returns the default maven home.
     * First checks the kompile maven managed maven install at:
     * $USER_HOME/.kompile/mvn
     *
     * Next checks M2_HOME
     *
     * Finally scans the path for a maven executable and returns
     * the bin parent directory.
     *
     * @return
     */
    public static File defaultMavenHome() {
        //first try maven from managed kompile install
        File userHome = new File(System.getProperty("user.home"),".kompile");
        File mvn = new File(userHome,"mvn");
        if(mvn.exists())
            return mvn;



        if(System.getenv().containsKey("M2_HOME")) {
            mvn = new File(System.getenv("M2_HOME"));
        }

        if(mvn.exists())
            return mvn;

        File executablePath = executableOnPath("mvn");
        //mvn home/bin/mvn is the main location expected for any maven distribution
        if(executablePath != null)
            return executablePath.getParentFile().getParentFile();

        return null;
    }


    /**
     * Resolve an environment variable in a string
     * with and replace it with the actual value.
     * The pattern is anything matching ${env.SOME_VALUE}
     * where SOME_VALUE can be anything matching the regex {@link #ENV_REGEX}
     * Note that anything not defined throws an exception.
     * @param value the value to parse
     * @return the value from the
     */
    public static String resolveEnvPropertyValue(String value) {
       if(value == null) {
           return null;
       }
        Matcher matcher = ENV_REGEX.matcher(value);
        List<String> allMatches = new ArrayList<>();
        while(matcher.find()) {
            String group = matcher.group();
            allMatches.add(group);
        }

        for(String match : allMatches) {
            String envKey = match.replace("${","")
                    .replace("}","");
            String[] keyValueSplit = envKey.split("\\.");
            String value2 = System.getenv(keyValueSplit[1]);
            if(value2 == null) {
                throw new IllegalStateException("No environment variable " + keyValueSplit[1] + " found!");
            }

            value = value.replace(match,value2);
        }

        return value;
    }

    /**
     * Resolve system property variable in a string
     * with and replace it with the actual value.
     * The pattern is anything matching ${SOME_VALUE}
     * where SOME_VALUE can be anything matching the regex {@link #PROP_REGEX}
     * Note that anything not defined throws an exception.
     * @param value the value to parse
     * @return the value resolved from the JVM property
     */
    public static String resolvePropertyValue(String value) {
        if(value == null)
            return null;
        Matcher matcher = PROP_REGEX.matcher(value);
        List<String> allMatches = new ArrayList<>();
        while(matcher.find()) {
            String group = matcher.group();
            allMatches.add(group);
        }

        for(String match : allMatches) {
            String envKey = match.replace("${","")
                    .replace("}","");
            if(envKey.contains("env.")) {
                String value2 = System.getenv(envKey.replace("env.",""));
                if(value2 == null) {
                    throw new IllegalStateException("No system property " + envKey + " found!");
                }

                value = value.replace(match,value2);

            } else {
                String value2 = System.getProperty(envKey);
                if(value2 == null) {
                    throw new IllegalStateException("No system property " + envKey + " found!");
                }

                value = value.replace(match,value2);

            }

        }

        return value;
    }

}
