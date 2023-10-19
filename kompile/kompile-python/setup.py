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

from distutils import sysconfig as dist_sysconfig
import sys
# # me make damn sure, that disutils does not mess with our
# # build process
#
dist_sysconfig.get_config_vars()['CFLAGS'] = ''
dist_sysconfig.get_config_vars()['OPT'] = ''
dist_sysconfig.get_config_vars()['PY_CFLAGS'] = ''
dist_sysconfig.get_config_vars()['PY_CORE_CFLAGS'] = ''
dist_sysconfig.get_config_vars()['CC'] = 'gcc'
dist_sysconfig.get_config_vars()['CXX'] = 'g++'
dist_sysconfig.get_config_vars()['BASECFLAGS'] = ''
dist_sysconfig.get_config_vars()['CCSHARED'] = '-fPIC'
dist_sysconfig.get_config_vars()['LDSHARED'] = 'gcc -shared'
dist_sysconfig.get_config_vars()['CPP'] = ''
dist_sysconfig.get_config_vars()['CPPFLAGS'] = ''
dist_sysconfig.get_config_vars()['BLDSHARED'] = ''
dist_sysconfig.get_config_vars()['CONFIGURE_LDFLAGS'] = ''
dist_sysconfig.get_config_vars()['LDFLAGS'] = ''
dist_sysconfig.get_config_vars()['PY_LDFLAGS'] = ''




from setuptools.extension import Extension
from setuptools import setup
from Cython.Build import cythonize
import numpy
import os
compiler_directives = {"language_level": 3, "embedsignature": True}

import sysconfig

_LIB_OUTPUT_PATH = 'LIB_OUTPUT_PATH'
_INCLUDE_PATH = 'INCLUDE_PATH'
include_list = [numpy.get_include()]
lib_list = []
if _LIB_OUTPUT_PATH in os.environ:
    lib_list.append(os.environ[_LIB_OUTPUT_PATH])
else:
    print('Unable to build. Please specify a library output path with environment variable LIB_OUTPUT_PATH')
    sys.exit(1)
if _INCLUDE_PATH in os.environ:
    include_list.append(os.environ[_INCLUDE_PATH])
else:
    print('Unable to build. Please specify an include path with environment variable INCLUDE_PATH')
    sys.exit(1)
print(sysconfig.get_paths()["purelib"] + "/kompile/kompile/interface/native")
extension = Extension('kompile.interface.native.interface',
                      extra_compile_args=[],
                      extra_link_args=["-Wl,-rpath=$ORIGIN", "-Wl,--enable-new-dtags"],
                      sources=['kompile/interface/native/interface.pyx'],
                      include_dirs=include_list,
                      library_dirs=lib_list,
                      libraries=['kompile_c_library','kompile-image'],
                      language='c'
                      )

ext_modules = cythonize(extension, compiler_directives=compiler_directives)

packages = ['kompile.interface.native',
            'kompile.interface.python']

if 'LIB_OUTPUT_PATH' in os.environ:
    files = os.listdir(os.environ[_LIB_OUTPUT_PATH])
    print('Copying files from library output path %s'.format(os.environ[_LIB_OUTPUT_PATH]))
    setup(name='kompile',
          version='0.0.1',
          author='Adam Gibson',
          author_email='adam@konduit.ai',
          packages=packages,
          include_package_data=True,
          package_data={
              'kompile.interface.native': files,
          },
          setup_requires=['wheel'],
          ext_modules=ext_modules)
else:
    setup(name='kompile',
          version='0.0.1',
          author='Adam Gibson',
          author_email='adam@konduit.ai',
          packages=packages,
          setup_requires=['wheel'],
          ext_modules=ext_modules)
