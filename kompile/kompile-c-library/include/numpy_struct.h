#ifndef __NUMPY_STRUCT_H
#include <stdio.h>

typedef struct  {
    int num_arrays;
    long *numpy_array_addresses;
    char **numpy_array_names;
    char **numpy_array_data_types;
    long **numpy_array_shapes;
    long *numpy_array_ranks;
} numpy_struct;

void print_numpy_struct(numpy_struct *toPrint) {
    printf("Number of arrays is %d\n",toPrint->num_arrays);
    for(int i = 0; i < toPrint->num_arrays; i++) {
        printf("Rank for item %d is %d\n",(int) i,(int) toPrint->numpy_array_ranks[i]);
        printf("Array address is %d\n",(int) toPrint->numpy_array_addresses[i]);
        printf("Array data type is %s\n", toPrint->numpy_array_data_types[i]);
        printf("Name of array is %s\n",toPrint->numpy_array_names[i]);
        for(int j = 0; j < toPrint->numpy_array_ranks[i]; j++) {
            printf("Rank for array %d and index %d is %d\n",i,j,(int) toPrint->numpy_array_shapes[i][j]);
        }

    }
}

typedef struct {
    void *native_ops_handle;
    void *pipeline_handle;
    void *executor_handle;
    void *isolate_thread;
    void *isolate;
} handles;


#endif