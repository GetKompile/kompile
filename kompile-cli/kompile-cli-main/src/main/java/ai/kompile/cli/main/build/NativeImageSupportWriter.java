/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.build;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Writes supporting files for GraalVM native image builds.
 * Extracted from RagPomGenerator to separate native image support file generation.
 */
public class NativeImageSupportWriter {

    /**
     * Writes the CGLIB native image patch script into the generated project.
     * This script is executed by maven-antrun-plugin during native image builds to:
     * 1. Add unsafeAllocated: true to all Spring CGLIB proxy entries in reflect-config.json
     * 2. Add CGLIB proxy classes to serialization-config.json (required by Objenesis
     *    StdInstantiatorStrategy which uses ReflectionFactory.newConstructorForSerialization())
     */
    public void writeCglibPatchScript(File projectDir) throws IOException {
        File scriptsDir = new File(projectDir, "src/main/resources/scripts");
        if (!scriptsDir.exists() && !scriptsDir.mkdirs()) {
            throw new IOException("Failed to create scripts directory: " + scriptsDir.getAbsolutePath());
        }
        File scriptFile = new File(scriptsDir, "patch-cglib-unsafe.py");
        try (FileWriter writer = new FileWriter(scriptFile)) {
            writer.write("#!/usr/bin/env python3\n");
            writer.write("import json, glob, os, sys\n\n");
            writer.write("def main():\n");
            writer.write("    if len(sys.argv) < 2:\n");
            writer.write("        print('Usage: patch-cglib-unsafe.py <build-directory>', file=sys.stderr)\n");
            writer.write("        sys.exit(1)\n");
            writer.write("    build_dir = sys.argv[1]\n");
            writer.write("    search_dirs = [\n");
            writer.write("        os.path.join(build_dir, 'classes', 'META-INF', 'native-image'),\n");
            writer.write("        os.path.join(build_dir, 'spring-aot', 'main', 'resources', 'META-INF', 'native-image'),\n");
            writer.write("    ]\n");
            writer.write("    configs = []\n");
            writer.write("    for sd in search_dirs:\n");
            writer.write("        configs.extend(glob.glob(os.path.join(sd, '**', 'reflect-config.json'), recursive=True))\n");
            writer.write("    if not configs:\n");
            writer.write("        print('WARN: No AOT reflect-config.json found - skipping CGLIB patch')\n");
            writer.write("        sys.exit(0)\n");
            writer.write("    total_reflect = 0\n");
            writer.write("    total_serial = 0\n");
            writer.write("    for cfg_path in configs:\n");
            writer.write("        with open(cfg_path, 'r') as f:\n");
            writer.write("            data = json.load(f)\n");
            writer.write("        cglib_classes = []\n");
            writer.write("        patched = 0\n");
            writer.write("        for entry in data:\n");
            writer.write("            name = entry.get('name', '')\n");
            writer.write("            if '$$SpringCGLIB$$' in name:\n");
            writer.write("                cglib_classes.append(name)\n");
            writer.write("                if not entry.get('unsafeAllocated', False):\n");
            writer.write("                    entry['unsafeAllocated'] = True\n");
            writer.write("                    patched += 1\n");
            writer.write("        if patched > 0:\n");
            writer.write("            with open(cfg_path, 'w') as f:\n");
            writer.write("                json.dump(data, f, indent=2)\n");
            writer.write("            print(f'Patched {patched} CGLIB entries with unsafeAllocated in {cfg_path}')\n");
            writer.write("            total_reflect += patched\n");
            writer.write("        if cglib_classes:\n");
            writer.write("            config_dir = os.path.dirname(cfg_path)\n");
            writer.write("            serial_path = os.path.join(config_dir, 'serialization-config.json')\n");
            writer.write("            existing = []\n");
            writer.write("            if os.path.exists(serial_path):\n");
            writer.write("                with open(serial_path, 'r') as f:\n");
            writer.write("                    existing = json.load(f)\n");
            writer.write("            existing_names = {e.get('name', '') for e in existing}\n");
            writer.write("            added = 0\n");
            writer.write("            for cls in cglib_classes:\n");
            writer.write("                if cls not in existing_names:\n");
            writer.write("                    existing.append({'name': cls, 'customTargetConstructorClass': 'java.lang.Object'})\n");
            writer.write("                    added += 1\n");
            writer.write("            if added > 0:\n");
            writer.write("                with open(serial_path, 'w') as f:\n");
            writer.write("                    json.dump(existing, f, indent=2)\n");
            writer.write("                print(f'Added {added} CGLIB entries to serialization-config.json')\n");
            writer.write("                total_serial += added\n");
            writer.write("    print(f'Total: {total_reflect} reflect patches, {total_serial} serialization additions')\n\n");
            writer.write("if __name__ == '__main__':\n");
            writer.write("    main()\n");
        }
        System.out.println("Generated CGLIB patch script: " + scriptFile.getAbsolutePath());
    }

    /**
     * Writes spring.properties to disable Objenesis for CGLIB proxy instantiation.
     * In GraalVM native image, Objenesis uses ReflectionFactory.newConstructorForSerialization()
     * which is problematic. Setting spring.objenesis.ignore=true forces Spring to use
     * constructor-based proxy instantiation instead.
     */
    public void writeSpringProperties(File projectDir) throws IOException {
        File resourcesDir = new File(projectDir, "src/main/resources");
        if (!resourcesDir.exists()) {
            resourcesDir.mkdirs();
        }
        File springProps = new File(resourcesDir, "spring.properties");
        // Only create if it doesn't exist - don't overwrite user customizations
        if (!springProps.exists()) {
            try (java.io.FileWriter writer = new java.io.FileWriter(springProps)) {
                writer.write("spring.objenesis.ignore=true\n");
            }
            System.out.println("Generated spring.properties: " + springProps.getAbsolutePath());
        }
    }
}
