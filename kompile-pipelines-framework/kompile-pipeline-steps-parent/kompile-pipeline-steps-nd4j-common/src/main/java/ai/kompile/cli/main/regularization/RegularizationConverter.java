/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.cli.main.regularization;


import org.apache.commons.io.FileUtils;
import org.nd4j.linalg.learning.regularization.Regularization;
import picocli.CommandLine;

import java.io.File;
import java.nio.charset.Charset;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;

public class RegularizationConverter implements CommandLine.ITypeConverter<Regularization> {
    @Override
    public Regularization convert(String s) throws Exception {
        File regularizationConfiguration = new File(s);
        if(!regularizationConfiguration.exists()) {
            System.err.println("Regularization configuration not found.");
            return null;
        }

        String json = FileUtils.readFileToString(regularizationConfiguration, Charset.defaultCharset());
        return ObjectMappers.getJsonMapper().convertValue(json, Regularization.class);
    }
}
