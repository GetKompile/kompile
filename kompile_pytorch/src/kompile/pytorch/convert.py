import torch


def convert_pytorch(model, tracing_input, file_path, **kwargs):
    torch.onnx.export(model,
                      tracing_input,
                      file_path,
                      export_params=True,
                      do_constant_folding=False,
                      opset_version=13,
                      **kwargs)
