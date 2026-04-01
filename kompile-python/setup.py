#  Copyright 2025 Kompile Inc.
#
#      Licensed under the Apache License, Version 2.0 (the "License");
#      you may not use this file except in compliance with the License.
#      You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#      Unless required by applicable law or agreed to in writing, software
#      distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
#      WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
#      License for the specific language governing permissions and limitations
#      under the License.
#
#      SPDX-License-Identifier: Apache-2.0

import os
import sys
from setuptools import setup, find_packages, Extension

# Try to import Cython for building the native interface extension.
# If Cython is not available, the native extension is skipped and only
# the pure-Python SDX runtime interface is installed.
try:
    from Cython.Build import cythonize
    import numpy as np
    HAS_CYTHON = True
except ImportError:
    HAS_CYTHON = False

ext_modules = []

if HAS_CYTHON and os.environ.get('KOMPILE_BUILD_CYTHON', '').lower() in ('1', 'true', 'yes'):
    # The Cython extension wraps the kompile C library which links to
    # the GraalVM native image shared library (libkompile_pipelines.so).
    # Build with: KOMPILE_BUILD_CYTHON=1 pip install -e .
    lib_dir = os.path.join(os.path.dirname(__file__), 'lib')
    include_dir = os.path.join(os.path.dirname(__file__), '..', 'kompile-c-library', 'include')

    ext_modules = cythonize([
        Extension(
            'kompile.interface.native.cython_interface',
            sources=['kompile/interface/native/interface.pyx'],
            include_dirs=[
                np.get_include(),
                include_dir,
                os.path.join(os.path.dirname(__file__), '..', 'kompile-c-library', 'include'),
            ],
            library_dirs=[lib_dir],
            libraries=['kompile_c_library', 'kompile_pipelines', 'kompile_lite'],
            runtime_library_dirs=[lib_dir] if sys.platform != 'darwin' else [],
        )
    ])

setup(
    name='kompile',
    version='0.1.0',
    author='Adam Gibson',
    author_email='adam@kompile.ai',
    description='Kompile Python SDK - pipeline execution and SDX runtime interface',
    long_description=open('README.md').read() if os.path.exists('README.md') else '',
    long_description_content_type='text/markdown',
    packages=find_packages(),
    python_requires='>=3.8',
    install_requires=[
        'numpy',
    ],
    setup_requires=['wheel'],
    ext_modules=ext_modules,
)
