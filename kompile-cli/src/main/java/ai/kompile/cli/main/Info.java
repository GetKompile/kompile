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

package ai.kompile.cli.main;

import ai.kompile.cli.main.util.OSResolver;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "info",mixinStandardHelpOptions = false,description = "Display information on current kompile installation.")
public class Info implements Callable<Integer> {

    public Info() {
    }

    public static File mavenDirectory() {
        return new File(homeDirectory(),"mvn");
    }

    public static File graalvmDirectory() {
        return new File(homeDirectory(),"graalvm");
    }


    public static File pythonDirectory() {
        return new File(homeDirectory(),"python");
    }
    public static File homeDirectory() {
        return new File(System.getProperty("user.home"),".kompile");
    }

    @Override
    public Integer call() throws Exception {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Kompile SDK information\n");
        File f = new File(System.getProperty("user.home"),".kompile");
        stringBuilder.append("Kompile Home directory location at " + f.getAbsolutePath() + " is installed: " + f.exists());
        stringBuilder.append("\n");
        stringBuilder.append("Graalvm Installed: " + Info.graalvmDirectory().exists());
        stringBuilder.append("\n");
        stringBuilder.append("Maven installed: " + Info.mavenDirectory().exists());
        stringBuilder.append("\n");
        stringBuilder.append("Python installed: " + Info.pythonDirectory().exists() + "\n");
        stringBuilder.append("Resolved install OS for install commands: " + OSResolver.os());
        stringBuilder.append("\n");
        System.out.println(stringBuilder);
        return 0;
    }

    public static void main(String...args) throws Exception {
        new Info().call();
    }

}
