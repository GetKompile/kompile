from kompile.interface.native.interface import PipelineRunner
import json
import numpy as np
with open('sequence-pipeline-python.json') as f:
    input_json = f.read()
    print(str(type(input_json)))
    pipeline_runner = PipelineRunner(pipeline_json=input_json)
    input_arr_dict = {'input': np.ones(1), 'input2': np.ones(1)}
    print('About to call run')
    output = pipeline_runner.run(input_arr_dict)
    print(output)
