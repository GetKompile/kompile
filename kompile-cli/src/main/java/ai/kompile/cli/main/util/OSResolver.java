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

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public class OSResolver {

    /**
     * TODO: add new properties files for specific
     * OSes based on the return values from this file.
     * The file names will be as follows:
     * programname.dependency.os.properties
     * where os is the value returned from this program.
     * For linux we will need to have some flexibility and
     * fallback to generic if a distro is found but the file
     * can be installed on any distro.
     * @return
     */

    public static String os() {
        if(OS.OS.isWindows()) {
            return "windows";
        } else if(OS.OS.isMac()) {
            return "mac";
        } else {
            return getDistro();
        }
    }

    @NotNull
    private static String getDistro() {
        String platformName = OS.OS.getPlatformName().toLowerCase(Locale.ROOT); //detect linux distro
        if(isRhelVariant(platformName)) {
            return "rhel";
        } else if(platformName.contains("debian")) {
            return "debian";
        } else if(platformName.contains("ubuntu")) {
            return "ubuntu";
        } else if(platformName.contains("arch")) {
            return "arch";
        } else {
            return "generic-linux";
        }
    }

    private static  boolean isRhelVariant(String platformName) {
        return platformName.contains("centos") ||
                platformName.contains("fedora") ||
                platformName.contains("rocky") ||
                platformName.contains("oracle");
    }

}
