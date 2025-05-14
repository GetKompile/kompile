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

package ai.kompile.cli.main.deps;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.Set;


public class DependencyTracker<T, D> extends AbstractDependencyTracker<T, D> {

    @Override
    protected IDependencyMap<T, D> newTMap() {
        return new DependencMapLinkedHash<T, D>();
    }

    @Override
    protected Set<T> newTSet() {
        return new LinkedHashSet<>();
    }

    @Override
    protected String toStringT(T t) {
        return t.toString();
    }

    @Override
    protected String toStringD(D d) {
        return d.toString();
    }
}
