from torch.utils.data import DataLoader
from kompile.interface.native.interface import PipelineRunner


class KompileTrainer(object):
    def __init__(self, pipeline_path='',
                 variable_names=[]):
        """
        The trainer takes in variables and runs kompile_pytorch based pipelines
        for training using pytorch's DataLoader.
         A DataLoader returns a list of arrays. We need to map these variables
         to names.
        :param pipeline_path:  the path to the pipeline json
        :param variable_names: the list of all variable names in list order
        for the data loader.
        """
        self.pipeline_path = pipeline_path
        with open(self.pipeline_path) as f:
            self.pipeline_runner = PipelineRunner(pipeline_json=f.read())
        self.variable_names = variable_names

    def fit(self, dataset: DataLoader):
        """
        Invokes a training epoch for the data provided
        by the data loader.
        This will convert every loaded dataset to a list of numpy arrays
         for passing in to the kompile_pytorch pipeline which takes in arrays
         by named dictionary.
        :param dataset:  the data loader to use to train
        :return:
        """
        for data in dataset:
            input_dict = {}
            for i in range(len(self.variable_names)):
                input_dict[self.variable_names[i]] = data[i].numpy()
            self.pipeline_runner.run(input_dict)
