Optimization Rules
-----------------------------

Context
--------------------------

Optimization of machine learning pipelines has an overall goal of increasing model efficiency either in terms of 
size or execution time.

Size based optimizations directly lead to better execution time and also help models run with reduced computing requirements.

Execution based optimizations focus more on making operations run faster on specific hardware.
This could generally be replacing kernels for certain operations to performing op fusion (making multiple ops in a graph 1 op)
enabling increased performance.

There are many ways to perform model optimization. 2 main groups come to mind: hardware based, graph level optimizations.

Hardware based optimizations focus more on increasing execution performance by providing more optimized kernels for specific hardware.
Graph level optimizations tend to focus on streamlining  a graph. Streamlining a graph falls in to a few buckets:
1. Reducing number of operations needed
2. Reducing data type precision to reduce compute requirements
3. Produce a new neural network that's small but approximates the original behavior of a bigger neural network.

