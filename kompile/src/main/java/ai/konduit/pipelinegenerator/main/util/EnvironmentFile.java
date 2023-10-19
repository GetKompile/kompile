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
import org.apache.commons.io.FileUtils;
import org.nd4j.common.base.Preconditions;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EnvironmentFile {

    public final static String BACKEND_ENVS_DIR = "backend-envs";

    public static File envFileForBackendAndPlatform(String backend,String platform) {
        File backendsDir = new File(Info.homeDirectory(),BACKEND_ENVS_DIR);
        File backendDir = new File(backendsDir,backend);
        File envFile = new File(backendDir,platform + ".env");
        return envFile;
    }

    /**
     * Write an environment for a given backend.
     * The directory structure will be as follows:
     * $USER/.kompile/backend-envs/$backend/classifier.env
     * @param backend the backend to write for
     * @param classifier the classifier to write for: OS-ARCH
     * @param key the key to write
     * @param value the value to write
     * @throws IOException
     */
    public static void writeEnvForClassifierAndBackend(String backend,String classifier,String key,String value) throws IOException {
        File userDir = new File(System.getProperty("user.home"),".kompile");
        if(!userDir.exists()) {
            Preconditions.checkState(userDir.mkdirs(),"Unable to make directory " + userDir.getAbsolutePath());
        }

        File backendsDir = new File(userDir,BACKEND_ENVS_DIR);
        if(!backendsDir.exists()) {
            Preconditions.checkState(backendsDir.mkdirs(),"Unable to make directory " + backendsDir.getAbsolutePath());
        }


        File backendDir = new File(backendsDir,backend);
        if(!backendDir.exists()) {
            Preconditions.checkState(backendDir.mkdirs(),"Unable to make directory " + backendDir.getAbsolutePath());
        }

        File envFile = new File(backendDir,classifier + ".env");
        if(!envFile.exists()) {
            envFile.createNewFile();
            FileUtils.write(envFile,key + "=" + value + "\n", Charset.defaultCharset());
        } else {
            Map<String,String> env = loadFromEnvFile(envFile);
            env.put(key,value);
            writeEnv(env,envFile);
        }
    }


    /**
     * Writes an environment map to the target file.
     * Creates a backup of the original file then
     * writes the new map to the file.
     * The specified file will be recreated
     * with the fresh contents.
     * @param env the environment to write
     * @param toWrite the file to write
     * @throws IOException
     */
    public static void writeEnv(Map<String,String> env,File toWrite) throws IOException {
        File backCopy = new File(toWrite.getParentFile(),toWrite.getName() + ".bak");
        FileUtils.copyFile(toWrite,backCopy);
        toWrite.delete();
        StringBuilder writeBuffer = new StringBuilder();
        for(Map.Entry<String,String> entry : env.entrySet()) {
            writeBuffer.append(entry.getKey() + "=" + entry.getValue() + "\n");
        }

        FileUtils.write(toWrite,writeBuffer.toString(),Charset.defaultCharset());
    }

    /**
     * Load an environment map from the file.
     * The file should be of the format:
     * KEY=VALUE
     *
     * Comments are also allowed. Any line
     * beginning with # will be skipped.
     * Any format outside this will throw an exception.
     * @param file the file to load from
     * @return the property map
     * @throws IOException
     */
    public static Map<String,String> loadFromEnvFile(File file) throws IOException {
        if(file == null || !file.exists()) {
            return new HashMap<>();
        }
        List<String> lines = FileUtils.readLines(file, Charset.defaultCharset());
        Map<String,String> ret = new HashMap<>();
        for(String line : lines) {
            if(line.startsWith("#")) {
                continue;
            }
            if(!line.contains("=")) {
                throw new IllegalStateException("File " + file + " has invalid line: " + line + " . All lines must be NAME=VALUE");
            }
            String[] split = line.split("=");
            ret.put(split[0],split[1]);
        }

        return ret;
    }



}
