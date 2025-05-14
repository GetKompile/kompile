/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.util;

import ai.kompile.cli.main.install.ProgramIndex;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

public class BackendInfo {

    private List<String> backends;
    private Map<String,List<String>> classifiers;



    public List<String> getClassifiersForBackend(String backend) {
        return classifiers.get(backend);
    }

    public List<String> getBackends() {
        return backends;
    }

    public void setBackends(List<String> backends) {
        this.backends = backends;
    }

    public Map<String, List<String>> getClassifiers() {
        return classifiers;
    }

    public void setClassifiers(Map<String, List<String>> classifiers) {
        this.classifiers = classifiers;
    }

    /**
     * Return the backend info
     * for a given platform.
     * This can be derived
     * from {@link OSResolver#os()}
     *
     * Backend info contains the classifiers
     * mapped by available backends.
     * @return the backend info mapped by classifiers.
     */
    public static BackendInfo backendClassifiersForBackend(String platform) {
        File indexForPlatform = ProgramIndex.pathToIndexForPlatform(platform);
        try (FileInputStream fileInputStream = new FileInputStream(indexForPlatform)){
            Properties properties = new Properties();
            properties.load(fileInputStream);
            String backends = properties.getProperty(platform + ".backends");
            String[] split = backends.split(",");
            List<String> backendList = new ArrayList<>(Arrays.asList(split));
            BackendInfo backendInfo = new BackendInfo();
            backendInfo.backends = backendList;
            Map<String,List<String>> backendClassifiers = new HashMap<>();
            for(String currBackend : backendList) {
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append(platform);
                stringBuilder.append(".");
                stringBuilder.append("backend");
                stringBuilder.append(".");
                stringBuilder.append(currBackend);
                stringBuilder.append(".");
                stringBuilder.append("classifier");
                String[] classifiers = properties.getProperty(stringBuilder.toString()).split(",");
                backendClassifiers.put(currBackend,Arrays.asList(classifiers));
            }

            backendInfo.classifiers = backendClassifiers;
            return backendInfo;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

}
