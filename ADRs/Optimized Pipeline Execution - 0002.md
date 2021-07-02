Context
--------------

In order to streamline execution of models, we need to figure out a way of outputting standardized results
such that the models perform faster while outputting what the user expects.
This may be done a number of ways from TVM/Glow to standardizing on a dl4j backend.

One other thing we can do is potentially test multiple backends and see which perform faster
with sample inputs.
This can be done quickly via creating models for different frameworks and createing an execution pipeline that leverages
the same frameworks with equivalent results and seeing what the throughput is.
