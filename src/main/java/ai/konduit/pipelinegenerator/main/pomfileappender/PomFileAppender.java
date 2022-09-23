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

package ai.konduit.pipelinegenerator.main.pomfileappender;

import java.util.Collections;
import java.util.List;

public interface PomFileAppender {

    enum DependencyType {
        PYTHON,
        TVM,
        OPENBLAS,
        JAVACPP,
        KONDUIT_SERVING_DSL,
        SUN_XML,
        JODA,
        ND4J_JACKSON,
        PYTHON4J,
        APACHE_COMMONS,
        KONDUIT_PYTHON,
        ND4J_CLASSLOADING

    }

    enum InitializeType {
        RUNTIME,
        BUILD_TIME
    }

    /**
     * Returns the dependency type for this appender.
     * @return
     */
    DependencyType dependencyType();

    /**
     * The list of classes to append
     * @return
     */
    List<String> classesToAppend();

    default List<String> classesToReInitialize() {
        return Collections.emptyList();
    }

    default void appendReInitialize(StringBuilder stringBuilder) {
        for(String clazz : classesToReInitialize()) {
            stringBuilder.append(String.format("--rerun-class-initialization-at-runtime=%s\n",clazz));

        }
    }

    default void append(StringBuilder stringBuilder) {
        for(String clazz : classesToAppend()) {
            if(initializeType() == InitializeType.BUILD_TIME)
                stringBuilder.append(String.format("--initialize-at-build-time=%s\n",clazz));
            else if(initializeType() == InitializeType.RUNTIME) {
                stringBuilder.append(String.format("--initialize-at-run-time=%s\n",clazz));
            }
        }
    }

    /**
     * Returns the initialize type determining
     * whether the
     * @return
     */
    InitializeType initializeType();

    /**
     * Represents whether the appender
     * adds javacpp native dependencies to the build.
     * Generally should be avoided at runtime.
     * @return
     */
    default boolean isNative() {
        return true;
    }



}
