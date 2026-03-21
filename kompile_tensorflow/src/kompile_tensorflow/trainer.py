from kompile.interface.native.interface import PipelineRunner
from tensorflow.python.data.ops.dataset_ops import Dataset


class KompileTrainer(object):
    def __init__(self, model_path='',
                 pipeline_path='',
                 variable_names=[],
                 output_names=[],
                 output_shapes={},
                 output_dtypes={},
                 pre_process_fns={},
                 library=None,
                 backend_preference=None):
        """
        The trainer takes in variables and runs kompile based pipelines
        for training using TensorFlow's Dataset.
        A Dataset returns arrays via as_numpy_iterator(). We need to map
        these variables to names.

        :param model_path:  path to an SDZ/SDNB model bundle
        :param pipeline_path: legacy path to pipeline JSON (deprecated, use model_path)
        :param variable_names: the list of all variable names in list order for the data loader
        :param output_names: the list of output tensor names
        :param output_shapes: dict mapping output name to shape tuple
        :param output_dtypes: dict mapping output name to numpy dtype
        :param pre_process_fns: a dictionary of pre process functions by variable name
        :param library: explicit path to SDX runtime shared library
        :param backend_preference: preferred backend: 'cpu', 'cuda', or 'amd'
        """
        self.pre_process_fns = pre_process_fns
        self.variable_names = variable_names

        if model_path:
            self.pipeline_runner = PipelineRunner(
                model_path=model_path,
                input_names=variable_names,
                output_names=output_names,
                output_shapes=output_shapes,
                output_dtypes=output_dtypes,
                library=library,
                backend_preference=backend_preference,
            )
        elif pipeline_path:
            # Legacy mode: read pipeline JSON
            with open(pipeline_path) as f:
                self.pipeline_runner = PipelineRunner(
                    pipeline_json=f.read(),
                    input_names=variable_names,
                    output_names=output_names,
                    output_shapes=output_shapes,
                    output_dtypes=output_dtypes,
                    library=library,
                    backend_preference=backend_preference,
                )
        else:
            raise ValueError("Either model_path or pipeline_path must be provided")

    def fit(self, dataset: Dataset):
        """
        Invokes a training epoch for the data provided
        by the dataset.
        This will convert every loaded dataset to a list of numpy arrays
        for passing in to the pipeline which takes in arrays
        by named dictionary.
        :param dataset:  the TensorFlow dataset to use to train
        :return:
        """
        for data in dataset.as_numpy_iterator():
            input_dict = {}
            for i in range(len(self.variable_names)):
                curr_arr = data[i]
                if self.pre_process_fns is not None and self.variable_names[i] in self.pre_process_fns:
                    curr_arr = self.pre_process_fns[self.variable_names[i]](curr_arr)
                input_dict[self.variable_names[i]] = curr_arr
            self.pipeline_runner.run(input_dict)

    def close(self):
        """Release SDX runtime resources."""
        if self.pipeline_runner is not None:
            self.pipeline_runner.close()
            self.pipeline_runner = None
