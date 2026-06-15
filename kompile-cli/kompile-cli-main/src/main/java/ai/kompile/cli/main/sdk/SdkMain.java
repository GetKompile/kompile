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

package ai.kompile.cli.main.sdk;

import picocli.CommandLine;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "sdk", subcommands = {
        SdkList.class,
        SdkDownload.class,
        SdkScaffold.class,
        SdkServe.class
}, mixinStandardHelpOptions = true,
        description = "SDX Runtime SDK and model bundle management.\n" +
                "Download platform-specific SDKs, SDZ model bundles, scaffold mobile apps, and serve models.")
public class SdkMain implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        new CommandLine(new SdkMain()).usage(System.out);
        return 0;
    }
}
