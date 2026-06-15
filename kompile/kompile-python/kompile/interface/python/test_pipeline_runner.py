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
