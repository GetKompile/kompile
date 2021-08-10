import sysconfig
# me make damn sure, that disutils does not mess with our
# build process

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


from distutils.core import setup
from distutils.extension import Extension
from Cython.Build import cythonize
import numpy

compiler_directives = {"language_level": 3, "embedsignature": True}
ext_modules = cythonize(
    Extension('kompile.interface.native.interface',
              extra_compile_args=[],
              sources=['kompile/interface/native/interface.pyx'],
              include_dirs=['./include',numpy.get_include()],
              library_dirs=['./lib'],
              libraries=['kompile_c_library','konduit-serving'],
              language='c'
              ), compiler_directives=compiler_directives)

packages = ['kompile.interface.native',
            'kompile.interface.python']

setup(name='kompile',
      packages=packages,
      ext_modules=ext_modules)
