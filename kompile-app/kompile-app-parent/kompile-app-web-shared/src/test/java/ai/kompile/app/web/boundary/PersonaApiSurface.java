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

package ai.kompile.app.web.boundary;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The API surface a persona app actually mounts, derived from its test classpath.
 *
 * <p>All three apps declare {@code @SpringBootApplication(scanBasePackages = "ai.kompile")}, and all
 * five web modules share the package {@code ai.kompile.app.web.controllers}. Package naming
 * therefore separates nothing — <b>the classpath is the boundary</b>. Reproducing Spring's component
 * scan over the same base package gives exactly the controller set the app will register, without
 * paying for a context refresh: booting these apps takes ~30-50s and pulls in JPA, Lucene, ND4J and
 * the model runtime, none of which has any bearing on which controllers are present.</p>
 *
 * <p>The consequence worth stating plainly: a persona app cannot mount a controller that is not on
 * its classpath, and cannot decline one that is. So adding a web module to a persona's pom is the
 * single act that changes its API surface, and that is what the {@code *PersonaBoundaryTest} classes
 * fence.</p>
 */
public final class PersonaApiSurface {

    /** The base package every app scans. */
    public static final String SCAN_ROOT = "ai.kompile";

    private static final List<Class<? extends java.lang.annotation.Annotation>> METHOD_MAPPINGS =
            List.of(GetMapping.class, PostMapping.class, PutMapping.class,
                    DeleteMapping.class, PatchMapping.class);

    private PersonaApiSurface() {}

    /**
     * Every class-level base path mounted by a controller on this classpath, sorted.
     *
     * <p>Paths are kept whole rather than truncated to a family: {@code /api/graph/hydration} is the
     * crawl manager's and {@code /api/graph/aggregate} is shared, so collapsing both to
     * {@code /api/graph} would erase the distinction the CLI's longest-prefix router depends on.</p>
     */
    public static SortedSet<String> mountedBasePaths() {
        SortedSet<String> paths = new TreeSet<>();
        controllers().values().forEach(paths::addAll);
        return paths;
    }

    /**
     * Controller class name to the base paths it mounts. Controllers with no class-level
     * {@code @RequestMapping} contribute their method-level paths instead, so a controller that maps
     * everything on its handler methods still shows up rather than silently scoring zero paths.
     */
    public static TreeMap<String, SortedSet<String>> controllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        // @RestController is meta-annotated @Controller, and AnnotationTypeFilter considers meta
        // annotations by default, so this one filter covers both.
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        TreeMap<String, SortedSet<String>> byController = new TreeMap<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(SCAN_ROOT)) {
            String className = candidate.getBeanClassName();
            if (className == null) {
                continue;
            }
            Class<?> type;
            try {
                type = Class.forName(className, false, PersonaApiSurface.class.getClassLoader());
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                // A controller whose own dependencies are absent cannot be registered either, so
                // treating it as unmounted matches what the running app would do.
                continue;
            }
            SortedSet<String> paths = pathsFor(type);
            if (!paths.isEmpty()) {
                byController.put(className, paths);
            }
        }
        return byController;
    }

    private static SortedSet<String> pathsFor(Class<?> type) {
        SortedSet<String> paths = new TreeSet<>();
        RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        if (classMapping != null) {
            paths.addAll(normalize(classMapping.path()));
            paths.addAll(normalize(classMapping.value()));
        }
        if (!paths.isEmpty()) {
            return paths;
        }
        for (Method method : type.getMethods()) {
            RequestMapping merged = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (merged != null) {
                paths.addAll(normalize(merged.path()));
                paths.addAll(normalize(merged.value()));
            }
        }
        return paths;
    }

    private static Set<String> normalize(String[] raw) {
        return Arrays.stream(raw)
                .filter(p -> p != null && !p.isBlank())
                .map(p -> p.startsWith("/") ? p : "/" + p)
                .collect(TreeSet::new, Set::add, Set::addAll);
    }

    /**
     * Base paths under {@code /api} that start with any of {@code prefixes}, matched on segment
     * boundaries so {@code /api/clustering} is not counted as a hit for {@code /api/cluster}.
     */
    public static SortedSet<String> matching(SortedSet<String> paths, Set<String> prefixes) {
        SortedSet<String> hits = new TreeSet<>();
        for (String path : paths) {
            for (String prefix : prefixes) {
                if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                    hits.add(path);
                    break;
                }
            }
        }
        return hits;
    }
}
