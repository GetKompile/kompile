import unittest
from kompile.interface.native.interface import PipelineRunner
import numpy as np
import json
class PipelineRunnerTestCase(unittest.TestCase):
    def test_runner(self):
        with open('/mnt/c/Users/agibs/Documents/GitHub/pipeline-generator/kompile-python/test_data/python-test-pipeline.json') as f:
            pipeline_runner = PipelineRunner(pipeline_json=f.read())
            input_arr_dict = {'input': np.ones((1,1))}
            for i,(name,array) in enumerate(input_arr_dict.items()):
                print('Type of name is ' + str(type(name)))
                print('Type of array is ' + str(type(array)))
            output = pipeline_runner.run(input_arr_dict)
            for i,(name,array) in enumerate(output.items()):
                print('Array with name is ' + str(name) + ' with array is ' + str(array))

if __name__ == '__main__':
    unittest.main()
