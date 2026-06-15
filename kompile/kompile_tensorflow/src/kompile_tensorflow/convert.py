from tensorflow.core.framework.graph_pb2 import GraphDef

from tensorflow.python.framework.convert_to_constants import convert_variables_to_constants_v2
import tensorflow as tf


def convert_saved_model(saved_model_dir, tag='serving_default') -> GraphDef:
    """
    Convert the saved model (expanded as a directory)
    to a frozen graph def
    :param saved_model_dir: the input model directory
    :param tag: the tag to load for the model
    :return:  the loaded graph def with all parameters in the model
    """
    saved_model = tf.saved_model.load(saved_model_dir)
    graph_def = saved_model.signatures[tag]
    frozen = convert_variables_to_constants_v2(graph_def)
    return frozen.graph.as_graph_def()


def convert_tensorflow_saved_model(saved_model_directory, output_directory, output_file):
    tf.io.write_graph(convert_saved_model(saved_model_directory), output_directory, output_file, as_text=False)
