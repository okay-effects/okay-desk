okay-desk native task interpreter, research profile v5

Based on Qwen/Qwen3-0.6B: https://huggingface.co/Qwen/Qwen3-0.6B
The Apache 2.0 license is included in LICENSE.

Changes: 4-bit weights, a reduced 38,285-token vocabulary, all 28 decoder layers retained,
and a rank-16 adapter trained to produce formal programs. The adapter is exported as separate
quantized low-rank factors. Model weights and adapter total approximately 305 MiB.

Inference runs locally through WebGPU, WebGL2 or CPU. Exact tools execute the authored programs.
Development results: 12/12 short premise/goal mutation tasks, 0/8 longer held-out word problems.
These are small development checks, not a general understanding benchmark. Interpretations can be wrong.
Physical-phone memory use and latency have not yet been verified.
