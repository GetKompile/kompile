#ifndef KOMPILE_C_LIBRARY_LIBRARY_H
#define KOMPILE_C_LIBRARY_LIBRARY_H
#include "konduit-serving.h"


void runPipelineWrapper(handles *handles,numpy_struct *input,numpy_struct *result);
void initPipelineWrapper(char *pipelinePath,handles *handles);


#endif //KOMPILE_C_LIBRARY_LIBRARY_H
