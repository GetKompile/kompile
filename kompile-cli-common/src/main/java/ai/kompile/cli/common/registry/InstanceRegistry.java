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

package ai.kompile.cli.common.registry;

import ai.kompile.cli.common.KompileHome;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Registry for tracking running Kompile application instances.
 * Instances are stored as JSON files in {@code ~/.kompile/instances/}.
 */
public class InstanceRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Registers a running instance.
     */
    public static void register(InstanceInfo info) throws IOException {
        File dir = KompileHome.instancesDirectory();
        dir.mkdirs();
        File file = new File(dir, info.getName() + ".json");
        MAPPER.writeValue(file, info);
    }

    /**
     * Unregisters an instance by name.
     */
    public static void unregister(String name) {
        File file = new File(KompileHome.instancesDirectory(), name + ".json");
        if (file.exists()) {
            file.delete();
        }
    }

    /**
     * Gets instance info by name.
     */
    public static InstanceInfo get(String name) throws IOException {
        File file = new File(KompileHome.instancesDirectory(), name + ".json");
        if (!file.exists()) {
            return null;
        }
        return MAPPER.readValue(file, InstanceInfo.class);
    }

    /**
     * Lists all registered instances.
     */
    public static List<InstanceInfo> listAll() throws IOException {
        File dir = KompileHome.instancesDirectory();
        List<InstanceInfo> instances = new ArrayList<>();
        if (!dir.exists()) {
            return instances;
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) {
            return instances;
        }
        for (File file : files) {
            try {
                instances.add(MAPPER.readValue(file, InstanceInfo.class));
            } catch (IOException e) {
                // Skip corrupt files
            }
        }
        return instances;
    }

    /**
     * Finds an instance by type.
     */
    public static InstanceInfo findByType(String type) throws IOException {
        for (InstanceInfo info : listAll()) {
            if (type.equals(info.getType())) {
                return info;
            }
        }
        return null;
    }

    /**
     * Finds an instance by port.
     */
    public static InstanceInfo findByPort(int port) throws IOException {
        for (InstanceInfo info : listAll()) {
            if (info.getPort() == port) {
                return info;
            }
        }
        return null;
    }
}
