from kompile.interface.native.interface import PipelineRunner
import numpy as np
import timeit

with open(
        'graph-step-sequence.json') as f:
    pipeline_runner = PipelineRunner(pipeline_json=f.read())
    input_arr_dict = {'input': np.ones((1, 3,22,224))}
    for i, (name, array) in enumerate(input_arr_dict.items()):
        print('Type of name is ' + str(type(name)))
        print('Type of array is ' + str(type(array)))
    num_runs = 10
    num_repetition = 3
    def run():
        output = pipeline_runner.run(input_arr_dict)
    duration = timeit.Timer(run).timeit(number=num_runs)
    avg_time = duration
    print('Average execution time was ' + str(avg_time))

    # for i, (name, array) in enumerate(output.items()):
    #     print('Array with name is ' + str(name) + ' with array is ' + str(array))
