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

package ai.konduit.pipelinegenerator.main.converter;

import ai.konduit.serving.pipeline.util.ObjectMappers;
import org.apache.commons.io.FileUtils;
import org.nd4j.linalg.learning.config.IUpdater;
import picocli.CommandLine;

import java.io.File;
import java.nio.charset.Charset;

public class IUpdaterTypeConverter implements CommandLine.ITypeConverter<IUpdater> {
    @Override
    public IUpdater convert(String value) throws Exception {
        File input = new File(value);
        if(!input.exists()) {
            throw new IllegalStateException("Path to updater configuration " + input.getAbsolutePath() + " did not exist! Please specify a path to a json file containing the configuration.");
        }
        String jsonContent = FileUtils.readFileToString(input, Charset.defaultCharset());
        return  ObjectMappers.fromJson(jsonContent,IUpdater.class);
    }
}
