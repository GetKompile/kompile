# Kompile Python

Python SDK for running inference via the [SDX runtime](https://github.com/deeplearning4j/deeplearning4j).

The SDK provides a `PipelineRunner` class that wraps the SDX runtime C ABI,
allowing you to run SDZ/SDNB model bundles with numpy arrays as inputs and outputs.

## Installation

```bash
pip install -e .
```

The SDX runtime shared library (`libnd4jcpu.so`, `libnd4jcuda.so`, etc.) must be
available on your system. You can configure its location via:

- `SdxRuntime(library='/path/to/libnd4jcpu.so')` or `PipelineRunner(library=...)`
- `SDX_RUNTIME_HOME` environment variable
- `SDX_RUNTIME_LIBRARY_DIR` environment variable
- System library path

## Usage

```python
from kompile.interface.native.interface import PipelineRunner
import numpy as np

# Load an SDZ model bundle
runner = PipelineRunner(
    model_path='/path/to/model.sdz',
    input_names=['input'],
    output_names=['output'],
    output_shapes={'output': (1, 10)},
    output_dtypes={'output': np.float32},
)

# Run inference with numpy arrays
input_arr = np.random.randn(1, 784).astype(np.float32)
result = runner.run({'input': input_arr})
print(result['output'])

# Clean up
runner.close()
```

### Context manager usage

```python
with PipelineRunner(
    model_path='/path/to/model.sdz',
    input_names=['input'],
    output_names=['output'],
    output_shapes={'output': (1, 10)},
    output_dtypes={'output': np.float32},
) as runner:
    result = runner.run({'input': np.ones((1, 784), dtype=np.float32)})
```

### Legacy pipeline JSON mode

For backward compatibility, you can pass a pipeline JSON config that contains
a `modelPath` key:

```python
import json

config = json.dumps({'modelPath': '/path/to/model.sdz'})
runner = PipelineRunner(
    pipeline_json=config,
    input_names=['input'],
    output_names=['output'],
    output_shapes={'output': (1, 10)},
)
result = runner.run({'input': np.ones((1, 784), dtype=np.float32)})
```

### Backend selection

```python
# Prefer CUDA backend
runner = PipelineRunner(
    model_path='/path/to/model.sdz',
    backend_preference='cuda',
    ...
)

# Or specify explicit library path
runner = PipelineRunner(
    model_path='/path/to/model.sdz',
    library='/opt/sdx/lib/libnd4jcuda.so',
    ...
)
```

## Architecture

The SDK uses the SDX runtime C ABI via Python's `ctypes`:

```
Python (numpy arrays)
    |
    v
PipelineRunner (kompile.interface.native.interface)
    |
    v
SdxRuntime / SdxModel / SdxContext (sdx_runtime.py)
    |
    v
SDX C ABI (libnd4jcpu.so / libnd4jcuda.so / libnd4jamd.so)
```

No compiled extensions (Cython, C) are needed - the SDK is pure Python with
numpy and ctypes.

## Framework Integration

- **PyTorch**: See `kompile_pytorch/` - `KompileTrainer` wraps DataLoader iteration
- **TensorFlow**: See `kompile_tensorflow/` - `KompileTrainer` wraps Dataset iteration
