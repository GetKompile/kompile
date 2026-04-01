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

"""
Kompile Python SDK interface built on the SDX runtime.

This replaces the former Cython/GraalVM native interface with a pure-Python
ctypes wrapper that uses the SDX runtime C ABI for model execution.

Usage is backward-compatible with the old PipelineRunner API:

    from kompile.interface.native.interface import PipelineRunner
    import numpy as np

    runner = PipelineRunner(
        model_path='/path/to/model.sdz',
        input_names=['input_1'],
        output_names=['output_1'],
        output_shapes={'output_1': (1, 10)},
        output_dtypes={'output_1': np.float32},
    )
    result = runner.run({'input_1': np.ones((1, 784), dtype=np.float32)})
    print(result['output_1'])

Legacy pipeline_json mode is also supported for backward compatibility:

    runner = PipelineRunner(pipeline_json=json_string)
    result = runner.run({'input_1': np.ones((32, 416, 416, 3))})
"""

import json
import os
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np

from .sdx_runtime import (
    ModelOptions,
    RunOptions,
    SdxContext,
    SdxModel,
    SdxRuntime,
    TensorView,
    SDX_BACKEND_AUTO,
)


class PipelineRunner:
    """Run inference on SDZ/SDNB model bundles via the SDX runtime.

    This class provides a dict-of-numpy-arrays interface for model execution,
    compatible with the legacy Cython-based PipelineRunner API.

    Parameters
    ----------
    model_path : str, optional
        Path to a .sdz or .sdnb model bundle file.
    pipeline_json : str, optional
        Legacy pipeline JSON string. If provided, the ``model_path`` is
        extracted from the JSON configuration. The JSON should contain
        a ``modelPath`` or ``model_path`` key, or a ``steps`` list with
        a step containing a ``modelPath``.
    input_names : list of str, optional
        Ordered names of input tensors. Used for dict-key mapping.
    output_names : list of str, optional
        Ordered names of output tensors. Used for dict-key mapping
        and context creation.
    output_shapes : dict, optional
        Mapping of output name to shape tuple. Required so the runner
        can pre-allocate output numpy arrays.
    output_dtypes : dict, optional
        Mapping of output name to numpy dtype. Defaults to float32.
    library : str, optional
        Explicit path to the SDX runtime shared library.
    backend_preference : str, optional
        Preferred backend: 'cpu', 'cuda', or 'amd'.
    backend : int, optional
        SDX backend constant (e.g. SDX_BACKEND_AUTO).
    model_options : ModelOptions, optional
        SDX ModelOptions for model loading.
    """

    def __init__(
        self,
        model_path: Optional[str] = None,
        pipeline_json: str = '',
        input_names: Optional[List[str]] = None,
        output_names: Optional[List[str]] = None,
        output_shapes: Optional[Dict[str, Tuple[int, ...]]] = None,
        output_dtypes: Optional[Dict[str, np.dtype]] = None,
        library: Optional[str] = None,
        backend_preference: Optional[str] = None,
        backend: int = SDX_BACKEND_AUTO,
        model_options: Optional[ModelOptions] = None,
    ):
        self._input_names = input_names or []
        self._output_names = output_names or []
        self._output_shapes = output_shapes or {}
        self._output_dtypes = output_dtypes or {}

        # Resolve model path
        resolved_path = model_path
        if not resolved_path and pipeline_json:
            resolved_path = self._extract_model_path(pipeline_json)

        if not resolved_path:
            raise ValueError(
                "Either model_path or pipeline_json (with a modelPath key) must be provided"
            )

        self._model_path = resolved_path

        # Initialize SDX runtime
        self._runtime = SdxRuntime(
            library=library,
            backend_preference=backend_preference,
        )

        # Load model
        opts = model_options or ModelOptions(backend=backend)
        self._model: SdxModel = self._runtime.load_model(self._model_path, opts)

        # Create reusable context
        ctx_outputs = self._output_names if self._output_names else None
        self._context: SdxContext = self._model.create_context(
            requested_outputs=ctx_outputs
        )

    @staticmethod
    def _extract_model_path(pipeline_json: str) -> Optional[str]:
        """Extract model path from legacy pipeline JSON config."""
        try:
            config = json.loads(pipeline_json)
        except (json.JSONDecodeError, TypeError):
            return None

        # Direct model path keys
        for key in ('modelPath', 'model_path', 'bundlePath', 'bundle_path'):
            if key in config:
                return config[key]

        # Check inside steps array
        steps = config.get('steps', [])
        for step in steps:
            step_config = step if isinstance(step, dict) else {}
            for key in ('modelPath', 'model_path', 'bundlePath', 'bundle_path'):
                if key in step_config:
                    return step_config[key]

        return None

    def run(
        self,
        name_to_ndarray: Dict[str, np.ndarray],
        run_options: Optional[RunOptions] = None,
    ) -> Dict[str, np.ndarray]:
        """Execute the model with the given named input arrays.

        Parameters
        ----------
        name_to_ndarray : dict
            Mapping of input tensor name to numpy ndarray.
        run_options : RunOptions, optional
            SDX run options for this execution.

        Returns
        -------
        dict
            Mapping of output tensor name to numpy ndarray results.
        """
        for name, arr in name_to_ndarray.items():
            if not isinstance(name, str):
                raise TypeError(f"Input name must be str, got {type(name)}")
            if not isinstance(arr, np.ndarray):
                raise TypeError(
                    f"Input value for '{name}' must be numpy.ndarray, got {type(arr)}"
                )

        # Build ordered input list
        if self._input_names:
            inputs = [name_to_ndarray[n] for n in self._input_names]
        else:
            inputs = list(name_to_ndarray.values())
            if not self._input_names:
                self._input_names = list(name_to_ndarray.keys())

        # Build ordered output list
        outputs = []
        out_names = self._output_names
        if not out_names:
            out_names = [f"output_{i}" for i in range(len(self._output_shapes))]
            if not out_names:
                # Infer single output with same shape as first input
                first_input = inputs[0]
                out_names = ['output']
                self._output_shapes = {'output': first_input.shape}
                self._output_dtypes = {'output': first_input.dtype}

        for name in out_names:
            shape = self._output_shapes.get(name)
            dtype = self._output_dtypes.get(name, np.float32)
            if shape is None:
                raise ValueError(
                    f"Output shape not specified for '{name}'. "
                    f"Provide output_shapes in constructor."
                )
            outputs.append(np.empty(shape, dtype=dtype))

        # Execute
        self._context.run(inputs, outputs, options=run_options)

        # Build result dict
        result = {}
        for i, name in enumerate(out_names):
            result[name] = outputs[i]

        return result

    def check_metrics(self):
        """Return execution report from the last run."""
        return self._context.execution_report()

    @property
    def abi_version(self) -> int:
        """Return the SDX runtime ABI version."""
        return self._runtime.abi_version()

    def close(self):
        """Release all SDX resources."""
        if self._context is not None:
            self._context.close()
            self._context = None
        if self._model is not None:
            self._model.close()
            self._model = None
        if self._runtime is not None:
            self._runtime.close()
            self._runtime = None

    def __del__(self):
        self.close()

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        self.close()
