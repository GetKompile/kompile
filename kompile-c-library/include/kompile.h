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
 *  limitations under the License.
 */

#ifndef __KOMPILE_H
#define __KOMPILE_H

#include <graal_isolate.h>
#include <numpy_struct.h>

#if defined(__cplusplus)
extern "C" {
#endif

/**
 * Initialize a pipeline from a JSON configuration file path.
 * The handles struct is populated with pipeline/executor handles for
 * subsequent runPipeline calls.
 *
 * @param thread  GraalVM isolate thread
 * @param handles Handles struct to populate
 * @param pipelinePath Path to pipeline JSON file, or inline JSON string
 * @return 0 on success, non-zero on failure
 */
int initPipeline(graal_isolatethread_t* thread, handles* handles, char* pipelinePath);

/**
 * Execute a pipeline with numpy array input and populate result arrays.
 *
 * @param thread  GraalVM isolate thread
 * @param handles Handles struct containing pipeline/executor handles
 * @param input   Input arrays as numpy_struct
 * @param result  Output arrays populated as numpy_struct (must be pre-allocated)
 * @return 0 on success, non-zero on failure
 */
int runPipeline(graal_isolatethread_t* thread, handles* handles, numpy_struct* input, numpy_struct* result);

/**
 * Print pipeline execution metrics to stdout.
 *
 * @param thread  GraalVM isolate thread
 */
void printMetrics(graal_isolatethread_t* thread);

/**
 * GraalVM VM locator symbol.
 */
void vmLocatorSymbol(graal_isolatethread_t* thread);

#if defined(__cplusplus)
}
#endif
#endif
