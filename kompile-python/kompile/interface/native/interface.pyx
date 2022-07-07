#  Copyright (c) 2022 Konduit K.K.
#
#      This program and the accompanying materials are made available under the
#      terms of the Apache License, Version 2.0 which is available at
#      https://www.apache.org/licenses/LICENSE-2.0.
#
#      Unless required by applicable law or agreed to in writing, software
#      distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
#      WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
#      License for the specific language governing permissions and limitations
#      under the License.
#
#      SPDX-License-Identifier: Apache-2.0

import numpy as np
from libc.stdlib cimport malloc,free
import ctypes
# Import the C-level symbols of numpy
cimport numpy as np
from libc.string cimport strcpy, strlen

import cython
# Numpy must be initialized. When using numpy from C or Cython you must
# _always_ do that, or you will have segfaults
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
    b'int8': b'SHORT',
    b'uint8': b'SHORT',
    b'bool': b'BOOL'
}




cdef extern from "<library.h>":
    ctypedef struct  numpy_struct:
        int num_arrays;
        long *numpy_array_addresses;
        char ** numpy_array_names;
        char ** numpy_array_data_types;
        long ** numpy_array_shapes;
        long *numpy_array_ranks;

    ctypedef struct handles:
        void *native_ops_handle;
        void *pipeline_handle;
        void *executor_handle;
        void *isolate_thread;
        void *isolate;


    cdef int initPipelineWrapper(char *pipeline_path,handles *handles2) nogil

    cdef void runPipelineWrapper(handles *handles2,numpy_struct *input_arrays, numpy_struct  *output_arrays) nogil

    cdef void checkMetricsWrapper(handles *handles2) nogil

@cython.cfunc
cdef handles * create_empty_handles():
    print('About to create handle')
    cdef handles *ret = <handles *> malloc(sizeof(handles))
    ret.native_ops_handle = <void *>(malloc(sizeof(int)))
    ret.pipeline_handle = <void *>(malloc(sizeof(int)))
    ret.executor_handle = <void *>(malloc(sizeof(int)))
    ret.isolate = <void *>(malloc(sizeof(int)))
    ret.isolate_thread = <void *> malloc(sizeof(int))
    print('Created handle')
    return ret

@cython.cfunc
cdef numpy_struct * create_empty_struct():
    cdef numpy_struct *ret = <numpy_struct *> malloc(sizeof(numpy_struct))
    return ret

@cython.cfunc
cdef np.ndarray create_array_from_struct(data_type_str,numpy_address,shape,rank):
    dtype = data_type_mapping[data_type_str]
    address = numpy_address
    Pointer = ctypes.POINTER(dtype)
    data_pointer = Pointer.from_address(address)
    pointer = data_pointer
    array_shape_c = shape
    shape_list = []
    for i in range(rank):
        shape_list.append(shape[i])
    np_array = np.ctypeslib.as_array(pointer, tuple(shape_list))
    return np_array


cdef print_char_array(char *input_arr,int length):
    for i in range(0,length):
        print(input_arr[i] + '\n')

@cython.cfunc
cdef numpy_struct * create_struct(name_to_ndarray):
    assert (type(name_to_ndarray) is dict) ,'Name to NDArray map is not a dict'
    cdef numpy_struct *ret = <numpy_struct *> malloc(sizeof(numpy_struct))
    num_arrays = len(name_to_ndarray)
    ret.num_arrays = num_arrays
    cdef long * addresses = <long *> (malloc(sizeof(long) * num_arrays))
    cdef char ** numpy_array_names = <char **> (malloc(sizeof(char) * num_arrays))
    cdef char ** numpy_array_data_types = <char **> (malloc(sizeof(char) * num_arrays))
    cdef long ** numpy_array_shapes = <long **> (malloc(sizeof(long) * num_arrays))
    cdef long * numpy_array_ranks = <long *> (malloc(sizeof(long) * num_arrays))

    ret.numpy_array_ranks = numpy_array_ranks
    ret.numpy_array_addresses = addresses
    ret.numpy_array_names = numpy_array_names
    ret.numpy_array_shapes = numpy_array_shapes
    ret.numpy_array_data_types = numpy_array_data_types
    arrays_allocated = 0
    array_lens = []
    array_shape_processed = 0
    names = []
    for i,(name, array) in enumerate(name_to_ndarray.items()):
        assert type(name) is str,'Name is not a string.'
        assert type(array) is np.ndarray,'Non ndarray found'
        names.append(name)
        array_lens.append(len(array.shape))
        ret.numpy_array_ranks[i] = len(array.shape)

    for i,(name,array) in enumerate(name_to_ndarray.items()):
        assert isinstance(array, np.ndarray)
        pointer_address = array.__array_interface__['data'][0]

        name += ('\0')
        temp_char_array = name.encode()
        copied_string = <char *>  malloc((len(name)) * sizeof(char))
        strcpy(copied_string,temp_char_array)
        ret.numpy_array_names[arrays_allocated] = copied_string
        ret.numpy_array_addresses[arrays_allocated] = pointer_address
        ret.numpy_array_data_types[arrays_allocated] = data_type_reverse_mapping[str(array.dtype).encode()]
        arrays_allocated += 1

    for i in range(0, num_arrays):
        shape = alloc_arrays(array_lens[i])
        array = name_to_ndarray[names[i]]
        for i2 in range(array_lens[i]):
            shape[i2] = array.shape[i2]
        ret.numpy_array_shapes[i] = shape
    return ret

@cython.cfunc
cdef long * alloc_arrays(long array_len):
    cdef long *shape = <long *> malloc(sizeof(long) * array_len)
    return shape

@cython.cfunc
cdef free_struct(numpy_struct input_struct):
    free(input_struct.numpy_array_addresses)
    free(input_struct.numpy_array_names)
    free(input_struct.numpy_array_data_types)
    free(input_struct.numpy_array_shapes)
    free(input_struct.numpy_array_ranks)
    free(&input_struct)

@cython.cfunc
cdef public convert_pointer_to_numpy(long *shape,length):
    if shape:
        ret = []
        for i in range(0,length):
            ret.append(shape[i])
        return ret
    else:
        raise Exception('Shape was null!')

@cython.cfunc
cdef public handles *  _init_pipeline(pipeline_json) except *:
    cdef handles *handles_to_use = create_empty_handles()
    print('About to call init pipeline')
    initPipelineWrapper(pipeline_json,handles_to_use)
    print('After calling init pipeline')
    return handles_to_use

cdef public _runMetricsCheck(handles *handles2):
    checkMetricsWrapper(handles2)

@cython.cfunc
cdef public _run(handles *handles2,name_to_ndarray):
    print('About to create struct')
    input_struct = create_struct(name_to_ndarray)
    result_struct = create_empty_struct()
    print('Created result')
    # python 3 only accepts byte strings, default was byte strings in 2
    print('About to run pipeline wrapper')
    runPipelineWrapper(handles2, input_struct, result_struct)
    print('Ran pipeline')
    ret = {}
    count = 0
    for i in range(0, result_struct.num_arrays):
        input_shape_list = []
        curr_shape = convert_pointer_to_numpy(result_struct.numpy_array_shapes[i],result_struct.numpy_array_ranks[i])
        for j in range(0, result_struct.numpy_array_ranks[i]):
            input_shape_list.append(curr_shape[j])
        ret[result_struct.numpy_array_names[i]] = create_array_from_struct(result_struct.numpy_array_data_types[i],
                                                                           result_struct.numpy_array_addresses[i],
                                                                           input_shape_list,
                                                                           result_struct.numpy_array_ranks[i])
    #free_struct(result_struct[0])
    #free_struct(input_struct[0])
    return ret

@cython.cfunc
cdef public _run_pipeline(handles *handles2,name_to_ndarray):
    return _run(handles2,name_to_ndarray)


cdef run_pipeline(handles *handles2,name_to_ndarray):
    for i,(name,array) in enumerate(name_to_ndarray.items()):
        assert type(name) is str, ' Passed in dictionary contains an invalid type ' + str(type(name))
        assert type(array) is np.ndarray,' Passed in dictionary contains an invalid type ' + str(type(array))
    return _run_pipeline(handles2,name_to_ndarray)

cdef class PipelineRunner(object):
    cdef handles *handles_ref
    cdef char *pipeline_json
    def __cinit__(self, pipeline_json=''):
        pipeline_json += '\0'
        temp_char_array = pipeline_json.encode()
        copied_string = <char *> malloc((len(temp_char_array)) * sizeof(char))
        strcpy(copied_string, temp_char_array)
        self.pipeline_json = copied_string
        handles = _init_pipeline(self.pipeline_json)
        self.handles_ref = handles
        print('Pipeline initialized')

    def check_metrics(self):
        _runMetricsCheck(self.handles_ref)

    def __dealloc__(self):
        free(self.handles_ref)
        free(self.pipeline_json)

    def run(self, name_to_ndarray):
       return run_pipeline(self.handles_ref,name_to_ndarray)
