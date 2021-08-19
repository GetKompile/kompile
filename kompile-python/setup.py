from distutils import sysconfig
# # me make damn sure, that disutils does not mess with our
# # build process
#
sysconfig.get_config_vars()['CFLAGS'] = ''
sysconfig.get_config_vars()['OPT'] = ''
sysconfig.get_config_vars()['PY_CFLAGS'] = ''
sysconfig.get_config_vars()['PY_CORE_CFLAGS'] = ''
sysconfig.get_config_vars()['CC'] = 'gcc'
sysconfig.get_config_vars()['CXX'] = 'g++'
sysconfig.get_config_vars()['BASECFLAGS'] = ''
sysconfig.get_config_vars()['CCSHARED'] = '-fPIC'
sysconfig.get_config_vars()['LDSHARED'] = 'gcc -shared'
sysconfig.get_config_vars()['CPP'] = ''
sysconfig.get_config_vars()['CPPFLAGS'] = ''
sysconfig.get_config_vars()['BLDSHARED'] = ''
sysconfig.get_config_vars()['CONFIGURE_LDFLAGS'] = ''
sysconfig.get_config_vars()['LDFLAGS'] = ''
sysconfig.get_config_vars()['PY_LDFLAGS'] = ''


from setuptools.extension import Extension
from setuptools import setup
from Cython.Build import cythonize
import numpy
import os
compiler_directives = {"language_level": 3, "embedsignature": True}

_LIB_OUTPUT_PATH = 'LIB_OUTPUT_PATH'
_INCLUDE_PATH = 'INCLUDE_PATH'
include_list = [numpy.get_include()]
lib_list = []
if _LIB_OUTPUT_PATH in os.environ:
    lib_list.append(os.environ[_LIB_OUTPUT_PATH])
else:
    raise Exception('Unable to build. Please specify a library output path with environment variable LIB_OUTPUT_PATH')

if _INCLUDE_PATH in os.environ:
    include_list.append(os.environ[_INCLUDE_PATH])
else:
    raise Exception('Unable to build. Please specify an include path with environment variable INCLUDE_PATH')


extension = Extension('kompile.interface.native.interface',
              extra_compile_args=[],
              sources=['kompile/interface/native/interface.pyx'],
              include_dirs=include_list,
              library_dirs=lib_list,
              libraries=['kompile_c_library','konduit-serving'],
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
