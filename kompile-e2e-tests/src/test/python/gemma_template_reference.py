"""Weights-free Jinja2 + llama.cpp vocabulary-only oracle, fed production context on stdin."""
import json
import pathlib
import sys
import hashlib

# The caller supplies the isolated installation, not the system package environment.
root = pathlib.Path(sys.argv[1]).resolve()
sys.path.insert(0, str(root))
import jinja2
from jinja2.sandbox import ImmutableSandboxedEnvironment
import llama_cpp
for module in (jinja2, llama_cpp):
    assert pathlib.Path(module.__file__).resolve().is_relative_to(root), module.__file__

payload = json.load(sys.stdin)
env = ImmutableSandboxedEnvironment(trim_blocks=True, lstrip_blocks=True)
def raise_exception(message):
    raise ValueError(message)
env.globals['raise_exception'] = raise_exception
rendered = env.from_string(payload['template']).render(**payload['context'])
# vocab_only bypasses tensor loading and inference. Special markers must remain token IDs.
model = llama_cpp.Llama(model_path=payload['gguf'], vocab_only=True, verbose=False)
try:
    ids = model.tokenize(rendered.encode('utf-8'), add_bos=False, special=True)
finally:
    model.close()
print(json.dumps({'rendered': rendered, 'ids': ids, 'jinjaVersion': jinja2.__version__,
                  'jinjaPath': jinja2.__file__, 'llamaVersion': llama_cpp.__version__,
                  'sha256': hashlib.sha256(rendered.encode('utf-8')).hexdigest()}))
