Python Interface
-----------------------------

Context
--------------------------
A user will get a pre generated single python file that contains a predict function that uses numpy arrays to interact with the ML pipeline
generated using graalvm's shared library support.

A binary is compiled with a python sdk generated that knows how to load and use the given file. Cpython interaction and associated dependencies will be used
in order to automatically configure the interface.

The reason for this is many deep learning frameworks accept and return numpy arrays as output. By providing a simple interface, it enables users to change 1 line of code to load
the model and call predict.

Generating a native shared library with graalvm:
https://www.graalvm.org/reference-manual/native-image/

CFFI for interacting with python:
https://cffi.readthedocs.io/en/latest/overview.html
