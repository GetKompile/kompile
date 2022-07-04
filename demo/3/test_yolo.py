from kompile.interface.native.interface import PipelineRunner
import json
import numpy as np
# input_1: 32,416,416,3
with open('yolov4-pipeline.json') as f:
    input_json = f.read()
    print(str(type(input_json)))
    pipeline_runner = PipelineRunner(pipeline_json=input_json)
    input_arr_dict = {'input_1': np.ones((32,416,416,3))}
    print('About to call run')
    output = pipeline_runner.run(input_arr_dict)
    print(output)
