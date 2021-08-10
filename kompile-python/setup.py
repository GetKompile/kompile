
from distutils.sysconfig import get_config_vars as default_get_config_vars


def remove_extra(x,target_string):
    if type(x) is str:
        # x.replace(" -pthread ") would be probably enough...
        # but we want to make sure we make it right for every input
        if x==target_string:
            return ""
        if x.startswith(" %s ".format(target_string)):
            return remove_extra(x[len("%s  ".format(target_string)):])
        if x.endswith(" %s ".format(target_string)):
            return remove_extra(x[:-len(" %s ".format(target_string))])
        return x.replace(" %s  ".format(target_string), " ")
    return x

def my_get_config_vars(*args):
  result = default_get_config_vars(*args)
  # sometimes result is a list and sometimes a dict:
  if type(result) is list:
     return [remove_extra(remove_extra(x,'-n1'),'.2-a+fp16+rcpc+dotprod+crypto:') for x in result]
  elif type(result) is dict:
     return {k : remove_extra(remove_extra(x,'-n1'),'.2-a+fp16+rcpc+dotprod+crypto:') for k,x in result.items()}
  else:
     raise Exception("cannot handle type"+type(result))

# 2.step: replace
import distutils.sysconfig as dsc
dsc.get_config_vars = my_get_config_vars

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
