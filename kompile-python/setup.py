from distutils.core import setup
from distutils.extension import Extension
from Cython.Build import cythonize
import numpy

compiler_directives = {"language_level": 3, "embedsignature": True}
ext_modules = cythonize(
    Extension('kompile.interface.native.interface',
              extra_compile_args=['-mtune=generic','-03'],
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
