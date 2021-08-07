import onnx
from onnxruntime.quantization import quantize_qat, QuantType
import os
model_fp32 = './agerace_v2_simplified.onnx'
assert os.path.exists(model_fp32)
model_quant = './agerace_v2_simplified.onnx_quant.onnx'
quantized_model = quantize_qat(model_fp32, model_quant)
if quantized_model is not None:
    with open(model_quant,'w+') as f:
        onnx.save_model(quantized_model,f)
else:
    print('Quantized model is none!')