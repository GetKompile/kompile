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

from setuptools import setup, find_packages

setup(
    name='kompile',
    version='0.1.0',
    author='Adam Gibson',
    author_email='adam@konduit.ai',
    description='Kompile Python SDK - SDX runtime interface for model inference',
    long_description=open('README.md').read(),
    long_description_content_type='text/markdown',
    packages=find_packages(),
    python_requires='>=3.8',
    install_requires=[
        'numpy',
    ],
    setup_requires=['wheel'],
)
