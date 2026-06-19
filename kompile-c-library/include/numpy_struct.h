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

#ifndef __NUMPY_STRUCT_H
#define __NUMPY_STRUCT_H

#include <stdio.h>

/**
 * Structure for passing numpy arrays between Python/C and Java.
 * Each field is a parallel array indexed by array index [0..num_arrays-1].
 */
typedef struct {
    int num_arrays;
    long *numpy_array_addresses;
    char **numpy_array_names;
    char **numpy_array_data_types;
    long **numpy_array_shapes;
    long *numpy_array_ranks;
} numpy_struct;

static void print_numpy_struct(numpy_struct *toPrint) {
    printf("Number of arrays is %d\n", toPrint->num_arrays);
    for (int i = 0; i < toPrint->num_arrays; i++) {
        printf("Rank for item %d is %ld\n", i, toPrint->numpy_array_ranks[i]);
        printf("Array address is %ld\n", toPrint->numpy_array_addresses[i]);
        printf("Array data type is %s\n", toPrint->numpy_array_data_types[i]);
        printf("Name of array is %s\n", toPrint->numpy_array_names[i]);
        for (int j = 0; j < toPrint->numpy_array_ranks[i]; j++) {
            printf("Shape for array %d dim %d is %ld\n", i, j, toPrint->numpy_array_shapes[i][j]);
        }
    }
}

/**
 * Opaque handles for managing GraalVM isolate and pipeline state.
 * pipeline_handle and executor_handle store integer IDs (cast to void*)
 * that map to Java-side pipeline/executor objects.
 */
typedef struct {
    void *native_ops_handle;
    void *pipeline_handle;
    void *executor_handle;
    void *isolate_thread;
    void *isolate;
} handles;

#endif /* __NUMPY_STRUCT_H */
