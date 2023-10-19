import torch


def convert_pytorch(model, tracing_input, file_path, **kwargs):
    torch.onnx.export(model,
                      tracing_input,
                      file_path,
                      verbose=True,
                      export_params=True,
                      keep_initializers_as_inputs=True,
                      do_constant_folding=False,
                      opset_version=13,
                      **kwargs)
