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
package ai.kompile.graph.reasoning.lifecycle;

import java.util.Objects;

/**
 * Store-agnostic ordering for the final graph lifecycle of a corpus crawl.
 *
 * <p>Both managed fact-sheet crawls and project-local {@code .kgraph} crawls use the same
 * sequence:</p>
 *
 * <ol>
 *   <li>learn and ground against the complete duplicate-preserving graph,</li>
 *   <li>resolve identities and canonicalize the graph,</li>
 *   <li>learn and ground once more only when canonicalization changed entity identity.</li>
 * </ol>
 *
 * <p>The pipeline deliberately knows nothing about persistence, Spring, crawl jobs, or a
 * particular learning engine. Callers provide adapters for those operations. A learning adapter
 * may mutate and return the same graph/scope object (the managed fact-sheet case) or return a new
 * one (the portable graph case).</p>
 */
public final class FinalGraphLearningResolutionPipeline {

    private FinalGraphLearningResolutionPipeline() {
    }

    /**
     * Execute the final learning and entity-resolution lifecycle.
     *
     * @param initialScope graph or store scope to process
     * @param learningEnabled whether the learning stages should run
     * @param learner adapter that learns/grounds the supplied scope
     * @param resolver adapter that resolves identities and returns the canonical scope
     */
    public static <S, L, R> Result<S, L, R> run(
            S initialScope,
            boolean learningEnabled,
            Learner<S, L> learner,
            Resolver<S, R> resolver) throws Exception {
        return run(initialScope, phase -> learningEnabled, learner, resolver);
    }

    /**
     * Execute the lifecycle with phase-specific learning decisions. This is useful when an earlier
     * hydration stage has already learned the pre-resolution graph, while a canonicalization change
     * must still force the post-resolution refresh.
     */
    public static <S, L, R> Result<S, L, R> run(
            S initialScope,
            LearningPolicy learningPolicy,
            Learner<S, L> learner,
            Resolver<S, R> resolver) throws Exception {
        S current = Objects.requireNonNull(initialScope, "initialScope");
        Objects.requireNonNull(learningPolicy, "learningPolicy");
        Objects.requireNonNull(learner, "learner");
        Objects.requireNonNull(resolver, "resolver");

        L preResolutionLearning = null;
        if (learningPolicy.shouldLearn(LearningPhase.PRE_RESOLUTION)) {
            LearningOutcome<S, L> learned = Objects.requireNonNull(
                    learner.learn(current, LearningPhase.PRE_RESOLUTION),
                    "pre-resolution learning outcome");
            current = Objects.requireNonNull(learned.scope(), "pre-resolution learned scope");
            preResolutionLearning = learned.detail();
        }

        ResolutionOutcome<S, R> resolved = Objects.requireNonNull(
                resolver.resolve(current), "resolution outcome");
        if (resolved.entitiesMerged() < 0) {
            throw new IllegalArgumentException("entitiesMerged must be >= 0");
        }
        current = Objects.requireNonNull(resolved.scope(), "resolved scope");

        L canonicalLearning = null;
        boolean canonicalLearningRan = resolved.graphChanged()
                && learningPolicy.shouldLearn(LearningPhase.POST_CANONICALIZATION);
        if (canonicalLearningRan) {
            LearningOutcome<S, L> learned = Objects.requireNonNull(
                    learner.learn(current, LearningPhase.POST_CANONICALIZATION),
                    "post-canonicalization learning outcome");
            current = Objects.requireNonNull(learned.scope(), "canonical learned scope");
            canonicalLearning = learned.detail();
        }

        return new Result<>(
                current,
                preResolutionLearning,
                resolved.detail(),
                canonicalLearning,
                resolved.entitiesMerged(),
                resolved.graphChanged(),
                canonicalLearningRan);
    }

    public enum LearningPhase {
        PRE_RESOLUTION,
        POST_CANONICALIZATION
    }

    @FunctionalInterface
    public interface LearningPolicy {
        boolean shouldLearn(LearningPhase phase);
    }

    @FunctionalInterface
    public interface Learner<S, L> {
        LearningOutcome<S, L> learn(S scope, LearningPhase phase) throws Exception;
    }

    @FunctionalInterface
    public interface Resolver<S, R> {
        ResolutionOutcome<S, R> resolve(S scope) throws Exception;
    }

    public record LearningOutcome<S, L>(S scope, L detail) {
        public LearningOutcome {
            Objects.requireNonNull(scope, "scope");
        }
    }

    public record ResolutionOutcome<S, R>(
            S scope,
            int entitiesMerged,
            boolean graphChanged,
            R detail) {
        public ResolutionOutcome {
            Objects.requireNonNull(scope, "scope");
            if (entitiesMerged < 0) {
                throw new IllegalArgumentException("entitiesMerged must be >= 0");
            }
            if (entitiesMerged > 0 && !graphChanged) {
                throw new IllegalArgumentException("entity merges must mark the graph as changed");
            }
        }

        public ResolutionOutcome(S scope, int entitiesMerged, R detail) {
            this(scope, entitiesMerged, entitiesMerged > 0, detail);
        }
    }

    public record Result<S, L, R>(
            S scope,
            L preResolutionLearning,
            R resolution,
            L canonicalLearning,
            int entitiesMerged,
            boolean graphChanged,
            boolean canonicalLearningRan) {
        public Result {
            Objects.requireNonNull(scope, "scope");
        }
    }
}
