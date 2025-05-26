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

package ai.kompile.pipelines.framework.core.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.io.IOUtils;
import org.nd4j.common.base.Preconditions;
import org.nd4j.shade.guava.collect.Streams;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;


@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ProcessUtils {

    private static final String OS = System.getProperty("os.name").toLowerCase();

    public static boolean isWindows() {
        return OS.contains("win");
    }

    public static boolean isMac() {
        return OS.contains("mac");
    }

    public static boolean isUnix() {
        return OS.contains("nix") || OS.contains("nux") || OS.contains("aix");
    }

    public static boolean isSolaris() {
        return OS.contains("sunos");
    }

    public static String runAndGetOutput(String... command){
        try {
            Process process = startProcessFromCommand(command);

            String output = getProcessOutput(process);
            String errorOutput = getProcessErrorOutput(process);

            int errorCode = process.waitFor();
            Preconditions.checkState(0 == errorCode,
                    String.format("Process exited with non-zero (%s) exit code. Details: %n%s%n%s", errorCode, output, errorOutput)
            );

            return output;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            System.exit(1);
            return null;
        }
    }

    private static Process startProcessFromCommand(String... command) throws IOException {
        return getProcessBuilderFromCommand(command).start();
    }

    private static ProcessBuilder getProcessBuilderFromCommand(String... command) {
        List<String> fullCommand = Streams.concat(getBaseCommand().stream(), Arrays.stream(command)).collect(Collectors.toList());
        return new ProcessBuilder(fullCommand);
    }

    private static List<String> getBaseCommand() {
        return Arrays.asList();
    }

    private static String getProcessOutput(Process process) throws IOException {
        return IOUtils.toString(process.getInputStream(), StandardCharsets.UTF_8);
    }

    private static String getProcessErrorOutput(Process process) throws IOException {
        return IOUtils.toString(process.getErrorStream(), StandardCharsets.UTF_8);
    }
}
