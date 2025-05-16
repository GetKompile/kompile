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

package ai.kompile.pipelines.steps.deeplearning4j.cli.converter;

import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import org.apache.commons.io.FileUtils;
import org.nd4j.linalg.learning.config.IUpdater;
import org.nd4j.linalg.schedule.ISchedule;
import picocli.CommandLine;

import java.io.File;
import java.nio.charset.Charset;

public class IScheduleTypeConverter implements CommandLine.ITypeConverter<ISchedule> {
    @Override
    public ISchedule convert(String value) throws Exception {
        File input = new File(value);
        if(!input.exists()) {
            throw new IllegalStateException("Path to schedule configuration " + input.getAbsolutePath() + " did not exist! Please specify a path to a json file containing the configuration.");
        }
        String jsonContent = FileUtils.readFileToString(input, Charset.defaultCharset());
        return  ObjectMappers.getJsonMapper().convertValue(jsonContent,ISchedule.class);
    }
}
