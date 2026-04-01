#  Copyright 2025 Kompile Inc.
#
#      Licensed under the Apache License, Version 2.0 (the "License");
#      you may not use this file except in compliance with the License.
#      You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#      Unless required by applicable law or agreed to in writing, software
#      distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
#      WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
#      License for the specific language governing permissions and limitations
#      under the License.
#
#      SPDX-License-Identifier: Apache-2.0

import numpy as np
from libc.stdlib cimport malloc, free
import ctypes
cimport numpy as np
from libc.string cimport strcpy, strlen

import cython
np.import_array()

# cython: c_string_type=str, c_string_encoding=ascii

data_type_mapping = {
    b'DOUBLE': ctypes.c_double,
    b'FLOAT': ctypes.c_float,
    b'HALF': ctypes.c_short,
    b'LONG': ctypes.c_long,
    b'INT': ctypes.c_int,
    b'SHORT': ctypes.c_short,
    b'BOOL': ctypes.c_bool
}

data_type_reverse_mapping = {
    b'float64': b'DOUBLE',
    b'float32': b'FLOAT',
    b'float16': b'HALF',
    b'int64': b'LONG',
    b'int32': b'INT',
    b'int16': b'SHORT',
    b'int8': b'SHORT',
    b'uint8': b'SHORT',
    b'bool': b'BOOL'
}


cdef extern from "<library.h>":
    ctypedef struct numpy_struct:
        int num_arrays
        long *numpy_array_addresses
        char **numpy_array_names
        char **numpy_array_data_types
        long **numpy_array_shapes
        long *numpy_array_ranks

    ctypedef struct handles:
        void *native_ops_handle
        void *pipeline_handle
        void *executor_handle
        void *isolate_thread
        void *isolate

    cdef void initPipelineWrapper(char *pipeline_path, handles *handles2) nogil
    cdef void runPipelineWrapper(handles *handles2, numpy_struct *input_arrays, numpy_struct *output_arrays) nogil
    cdef void checkMetricsWrapper(handles *handles2) nogil

    # Kompile Lite API
    cdef void initLiteWrapper(char *config_path, handles *handles2) nogil
    cdef char* chatWrapper(handles *handles2, char *message, char *session_id) nogil
    cdef int ingestDocumentWrapper(handles *handles2, char *file_path) nogil
    cdef char* ragQueryWrapper(handles *handles2, char *query, int max_results) nogil
    cdef char* graphQueryWrapper(handles *handles2, char *query, int k) nogil
    cdef int buildGraphWrapper(handles *handles2) nogil
    cdef void freeCStringWrapper(char *str) nogil


@cython.cfunc
cdef handles *create_empty_handles():
    cdef handles *ret = <handles *> malloc(sizeof(handles))
    ret.native_ops_handle = NULL
    ret.pipeline_handle = NULL
    ret.executor_handle = NULL
    ret.isolate = NULL
    ret.isolate_thread = NULL
    return ret


@cython.cfunc
cdef numpy_struct *create_empty_struct():
    cdef numpy_struct *ret = <numpy_struct *> malloc(sizeof(numpy_struct))
    ret.num_arrays = 0
    ret.numpy_array_addresses = NULL
    ret.numpy_array_names = NULL
    ret.numpy_array_data_types = NULL
    ret.numpy_array_shapes = NULL
    ret.numpy_array_ranks = NULL
    return ret


@cython.cfunc
cdef np.ndarray create_array_from_struct(data_type_str, numpy_address, shape, rank):
    dtype = data_type_mapping[data_type_str]
    Pointer = ctypes.POINTER(dtype)
    data_pointer = Pointer.from_address(numpy_address)
    shape_list = []
    for i in range(rank):
        shape_list.append(shape[i])
    np_array = np.ctypeslib.as_array(data_pointer, tuple(shape_list))
    return np_array


@cython.cfunc
cdef numpy_struct *create_struct(name_to_ndarray):
    assert (type(name_to_ndarray) is dict), 'Name to NDArray map is not a dict'
    cdef numpy_struct *ret = <numpy_struct *> malloc(sizeof(numpy_struct))
    num_arrays = len(name_to_ndarray)
    ret.num_arrays = num_arrays

    cdef long *addresses = <long *> malloc(sizeof(long) * num_arrays)
    cdef char **numpy_array_names = <char **> malloc(sizeof(char *) * num_arrays)
    cdef char **numpy_array_data_types = <char **> malloc(sizeof(char *) * num_arrays)
    cdef long **numpy_array_shapes = <long **> malloc(sizeof(long *) * num_arrays)
    cdef long *numpy_array_ranks = <long *> malloc(sizeof(long) * num_arrays)

    ret.numpy_array_ranks = numpy_array_ranks
    ret.numpy_array_addresses = addresses
    ret.numpy_array_names = numpy_array_names
    ret.numpy_array_shapes = numpy_array_shapes
    ret.numpy_array_data_types = numpy_array_data_types

    array_lens = []
    names = []
    for i, (name, array) in enumerate(name_to_ndarray.items()):
        assert type(name) is str, 'Name is not a string.'
        assert type(array) is np.ndarray, 'Non ndarray found'
        names.append(name)
        array_lens.append(len(array.shape))
        ret.numpy_array_ranks[i] = len(array.shape)

    for i, (name, array) in enumerate(name_to_ndarray.items()):
        assert isinstance(array, np.ndarray)
        pointer_address = array.__array_interface__['data'][0]

        name += '\0'
        temp_char_array = name.encode()
        copied_string = <char *> malloc((len(name)) * sizeof(char))
        strcpy(copied_string, temp_char_array)
        ret.numpy_array_names[i] = copied_string
        ret.numpy_array_addresses[i] = pointer_address
        ret.numpy_array_data_types[i] = data_type_reverse_mapping[str(array.dtype).encode()]

    for i in range(0, num_arrays):
        shape = alloc_arrays(array_lens[i])
        array = name_to_ndarray[names[i]]
        for i2 in range(array_lens[i]):
            shape[i2] = array.shape[i2]
        ret.numpy_array_shapes[i] = shape

    return ret


@cython.cfunc
cdef long *alloc_arrays(long array_len):
    cdef long *shape = <long *> malloc(sizeof(long) * array_len)
    return shape


@cython.cfunc
cdef free_struct(numpy_struct *input_struct):
    if input_struct == NULL:
        return
    if input_struct.num_arrays > 0:
        for i in range(input_struct.num_arrays):
            if input_struct.numpy_array_names != NULL and input_struct.numpy_array_names[i] != NULL:
                free(input_struct.numpy_array_names[i])
            if input_struct.numpy_array_shapes != NULL and input_struct.numpy_array_shapes[i] != NULL:
                free(input_struct.numpy_array_shapes[i])
        if input_struct.numpy_array_addresses != NULL:
            free(input_struct.numpy_array_addresses)
        if input_struct.numpy_array_names != NULL:
            free(input_struct.numpy_array_names)
        if input_struct.numpy_array_data_types != NULL:
            free(input_struct.numpy_array_data_types)
        if input_struct.numpy_array_shapes != NULL:
            free(input_struct.numpy_array_shapes)
        if input_struct.numpy_array_ranks != NULL:
            free(input_struct.numpy_array_ranks)
    free(input_struct)


@cython.cfunc
cdef public convert_pointer_to_numpy(long *shape, length):
    if shape:
        ret = []
        for i in range(0, length):
            ret.append(shape[i])
        return ret
    else:
        raise Exception('Shape was null!')


@cython.cfunc
cdef public handles *_init_pipeline(pipeline_json) except *:
    cdef handles *handles_to_use = create_empty_handles()
    initPipelineWrapper(pipeline_json, handles_to_use)
    return handles_to_use


cdef public _runMetricsCheck(handles *handles2):
    checkMetricsWrapper(handles2)


@cython.cfunc
cdef public _run(handles *handles2, name_to_ndarray):
    input_struct = create_struct(name_to_ndarray)
    result_struct = create_empty_struct()
    runPipelineWrapper(handles2, input_struct, result_struct)

    ret = {}
    for i in range(0, result_struct.num_arrays):
        curr_shape = convert_pointer_to_numpy(result_struct.numpy_array_shapes[i], result_struct.numpy_array_ranks[i])
        input_shape_list = []
        for j in range(0, result_struct.numpy_array_ranks[i]):
            input_shape_list.append(curr_shape[j])
        ret[result_struct.numpy_array_names[i]] = create_array_from_struct(
            result_struct.numpy_array_data_types[i],
            result_struct.numpy_array_addresses[i],
            input_shape_list,
            result_struct.numpy_array_ranks[i])

    free_struct(input_struct)
    # Don't free result_struct arrays since they're backed by Java memory
    free(result_struct)
    return ret


@cython.cfunc
cdef public _run_pipeline(handles *handles2, name_to_ndarray):
    return _run(handles2, name_to_ndarray)


cdef run_pipeline(handles *handles2, name_to_ndarray):
    for i, (name, array) in enumerate(name_to_ndarray.items()):
        assert type(name) is str, 'Passed in dictionary contains an invalid type ' + str(type(name))
        assert type(array) is np.ndarray, 'Passed in dictionary contains an invalid type ' + str(type(array))
    return _run_pipeline(handles2, name_to_ndarray)


cdef class PipelineRunner(object):
    """
    Runs a Kompile pipeline via GraalVM native image shared library.

    Usage:
        runner = PipelineRunner('/path/to/pipeline.json')
        result = runner.run({'input': np.array([1.0, 2.0, 3.0], dtype=np.float32)})
    """
    cdef handles *handles_ref
    cdef char *pipeline_json

    def __cinit__(self, pipeline_json=''):
        pipeline_json += '\0'
        temp_char_array = pipeline_json.encode()
        copied_string = <char *> malloc((len(temp_char_array)) * sizeof(char))
        strcpy(copied_string, temp_char_array)
        self.pipeline_json = copied_string
        self.handles_ref = _init_pipeline(self.pipeline_json)

    def check_metrics(self):
        """Print pipeline execution metrics."""
        _runMetricsCheck(self.handles_ref)

    def __dealloc__(self):
        if self.handles_ref != NULL:
            free(self.handles_ref)
        if self.pipeline_json != NULL:
            free(self.pipeline_json)

    def run(self, name_to_ndarray):
        """
        Run the pipeline with a dictionary of named numpy arrays.

        Args:
            name_to_ndarray: dict mapping string names to numpy arrays

        Returns:
            dict mapping string names to numpy arrays (pipeline output)
        """
        return run_pipeline(self.handles_ref, name_to_ndarray)


cdef class KompileLite(object):
    """
    Kompile Lite client — chat, RAG, and Graph RAG via GraalVM native image.

    Usage:
        lite = KompileLite('/path/to/config')
        response = lite.chat('What is machine learning?')
        lite.ingest('/path/to/document.pdf')
        results = lite.rag_query('neural networks', max_results=5)
        lite.build_graph()
        graph_results = lite.graph_query('relationships between concepts')
    """
    cdef handles *handles_ref
    cdef char *config_path_str

    def __cinit__(self, config_path=''):
        config_path += '\0'
        temp = config_path.encode()
        self.config_path_str = <char *> malloc((len(temp)) * sizeof(char))
        strcpy(self.config_path_str, temp)
        self.handles_ref = create_empty_handles()
        initLiteWrapper(self.config_path_str, self.handles_ref)

    def __dealloc__(self):
        if self.handles_ref != NULL:
            free(self.handles_ref)
        if self.config_path_str != NULL:
            free(self.config_path_str)

    def chat(self, message, session_id='default'):
        """Send a chat message and get a response."""
        cdef char *msg = message.encode()
        cdef char *sid = session_id.encode()
        cdef char *result = chatWrapper(self.handles_ref, msg, sid)
        if result == NULL:
            return None
        py_result = result.decode('utf-8')
        freeCStringWrapper(result)
        return py_result

    def ingest(self, file_path):
        """Ingest a document file into the vector store."""
        cdef char *fp = file_path.encode()
        return ingestDocumentWrapper(self.handles_ref, fp)

    def rag_query(self, query, max_results=5):
        """Query the RAG system directly."""
        cdef char *q = query.encode()
        cdef char *result = ragQueryWrapper(self.handles_ref, q, max_results)
        if result == NULL:
            return None
        py_result = result.decode('utf-8')
        freeCStringWrapper(result)
        return py_result

    def graph_query(self, query, k=5):
        """Query the knowledge graph."""
        cdef char *q = query.encode()
        cdef char *result = graphQueryWrapper(self.handles_ref, q, k)
        if result == NULL:
            return None
        py_result = result.decode('utf-8')
        freeCStringWrapper(result)
        return py_result

    def build_graph(self):
        """Trigger knowledge graph construction from indexed documents."""
        return buildGraphWrapper(self.handles_ref)
