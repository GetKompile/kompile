"""Opt-in exact-ID llama.cpp oracle and full-vocabulary comparison; no sampling."""
import argparse
import json
import pathlib
import time
import sys
import os
import hashlib
# Hard process bound also covers native model load/eval (ctypes releases the GIL).
import threading
import numpy as np

p = argparse.ArgumentParser()
p.add_argument('mode', choices=['reference', 'compare', 'trace', 'comparetrace'])
p.add_argument('--root', required=True)
p.add_argument('--case', default='short')
p.add_argument('--model')
p.add_argument('--isolated-root')
p.add_argument('--freeze-source', help='Existing template parity JSON; never regenerate a request')
p.add_argument('--steps', type=int, default=5)
p.add_argument('--timeout', type=int, default=180)
a = p.parse_args()
assert 1 <= a.steps <= 256
assert 1 <= a.timeout <= 900
watchdog = threading.Timer(a.timeout, lambda: os._exit(124))
watchdog.daemon = True
watchdog.start()
root = pathlib.Path(a.root)
name = a.case
if a.freeze_source:
    source = pathlib.Path(a.freeze_source)
    frozen = json.loads(source.read_text())
    # Exclusive creation prevents reruns silently replacing the frozen request.
    for suffix, content in [('prompt.txt', frozen['rendered']), ('ids.json', json.dumps(frozen['ids']))]:
        with (root / (name + '.' + suffix)).open('x') as out:
            out.write(content)
ids = json.loads((root / (name + '.ids.json')).read_text())
hashes = {suffix: hashlib.sha256((root / (name + '.' + suffix)).read_bytes()).hexdigest()
          for suffix in ('prompt.txt', 'ids.json')}
print('FROZEN_INPUT_SHA256', json.dumps(hashes), flush=True)
if a.mode in ('reference', 'trace'):
    import llama_cpp
    import llama_cpp.llama_cpp as native
    isolated = pathlib.Path(a.isolated_root).resolve()
    for module in (llama_cpp, np):
        assert pathlib.Path(module.__file__).resolve().is_relative_to(isolated), module.__file__
    assert pathlib.Path(native._lib._name).resolve().is_relative_to(isolated)
    print('REFERENCE_IMPORT', llama_cpp.__version__, llama_cpp.__file__, np.__file__, native._lib._name, flush=True)
    capacity = ((len(ids) + a.steps + 255) // 256) * 256
    print('REFERENCE_LOAD_START', 'capacity', capacity, flush=True)
    captured = []
    if a.mode == 'trace':
        import ctypes as c
        get_name = native._lib.ggml_get_name; get_name.argtypes=[c.c_void_p]; get_name.restype=c.c_char_p
        elements = native._lib.ggml_nelements; elements.argtypes=[c.c_void_p]; elements.restype=c.c_int64
        nbytes = native._lib.ggml_nbytes; nbytes.argtypes=[c.c_void_p]; nbytes.restype=c.c_size_t
        get = native._lib.ggml_backend_tensor_get
        get.argtypes=[c.c_void_p,c.c_void_p,c.c_size_t,c.c_size_t]; get.restype=None
        @native.ggml_backend_sched_eval_callback
        def callback(t, ask, data):
            label=get_name(t).decode()
            size=nbytes(t); count=elements(t)
            selected=(size==count*4 and count<1000000)
            if ask: return selected
            if selected:
                buf=(c.c_float*count)(); get(t,buf,0,size)
                values=np.ctypeslib.as_array(buf).copy()
                index=len(captured)
                values.astype('>f4').tofile(root / f'{name}.reftrace.{index}.f32be')
                captured.append({'index':index,'name':label,'elements':count,'finite':int(np.isfinite(values).sum())})
            return True
        original=native.llama_init_from_model
        def traced_init(model, params):
            params.cb_eval=callback
            return original(model,params)
        native.llama_init_from_model=traced_init
    start = time.monotonic()
    model = llama_cpp.Llama(model_path=a.model, logits_all=True, n_gpu_layers=0,
                           n_ctx=capacity, n_batch=min(capacity,512), n_ubatch=min(capacity,512),
                           n_threads=8, n_threads_batch=8, type_k=native.GGML_TYPE_F16,
                           type_v=native.GGML_TYPE_F16, verbose=True)
    print('REFERENCE_LOAD_SECONDS', time.monotonic()-start, flush=True)
    own_ids = model.tokenize((root / (name + '.prompt.txt')).read_bytes(), add_bos=False, special=True)
    mismatch = next((i for i,(x,y) in enumerate(zip(ids,own_ids)) if x!=y), None)
    print('REFERENCE_TOKENIZATION', len(ids), len(own_ids), 'firstDifference', mismatch, flush=True)
    assert own_ids == ids, 'Tokenizer discrepancy: stop before numerical comparison'
    if a.mode == 'trace':
        try:
            model.eval(ids)
            (root / (name+'.reftrace.json')).write_text(json.dumps(captured,indent=2))
            print('REFERENCE_TRACE', json.dumps(captured),flush=True)
        finally:
            model.close()
        sys.exit(0)
    forced = []
    metadata = {'version': llama_cpp.__version__, 'module': llama_cpp.__file__, 'library': native._lib._name,
                'capacity': capacity, 'n_gpu_layers':0, 'type_k':'F16', 'type_v':'F16',
                'activations':'llama.cpp CPU native (not forced HALF)', 'logits':'float32 model-softcapped, no penalties/constraints/sampling',
                'prompt_tokens':len(ids), 'inputSha256':hashes, 'maxSteps':a.steps,
                'positions':'zero-based; existing BOS in frozen IDs; no added BOS', 'steps':[]}
    try:
        for step in range(a.steps):
            start = time.monotonic()
            model.eval(ids if step==0 else [forced[-1]])
            logits = model.scores[model.n_tokens-1].copy().astype(np.float32)
            assert logits.shape == (model.n_vocab(),)
            logits.astype('>f4').tofile(root / f'{name}.reference.{step}.f32be')
            top = np.argsort(-logits, kind='stable')[:10]
            record = {'step':step,'n_tokens':model.n_tokens,'seconds':time.monotonic()-start,
                      'finite':int(np.isfinite(logits).sum()), 'vocab':len(logits),
                      'top10':[(int(i),float(logits[i])) for i in top],
                      'margin':float(logits[top[0]]-logits[top[1]])}
            metadata['steps'].append(record)
            print('REFERENCE_STEP',json.dumps(record),flush=True)
            assert np.isfinite(logits).all()
            forced.append(int(top[0]))
            metadata['continuation'] = model.detokenize(forced, special=True).decode('utf-8', errors='replace')
            record['piece'] = model.detokenize([forced[-1]], special=True).decode('utf-8', errors='replace')
            if record['piece'] in ('<tool_call|>', '<tool|>', '<turn|>', '<|turn>', '<eos>'):
                metadata['stopReason'] = record['piece']
                break
        metadata.setdefault('stopReason', 'token-bound')
        metadata['generatedIds'] = forced
        print('REFERENCE_CONTINUATION', json.dumps(metadata['continuation']), flush=True)
        # There is one logit checkpoint per generated token, before that token is fed.
        (root / (name+'.forced.json')).write_text(json.dumps(forced[:-1]))
        (root / (name+'.reference.json')).write_text(json.dumps(metadata,indent=2))
    finally:
        model.close()
elif a.mode == 'comparetrace':
    rows=[]
    for variable,index in [('embedded',0),('embed_scaled',4),('attn_norm_0',6),('q_proj_0',7),('q_norm_0',10),('q_rope_0',11)]:
        r=np.fromfile(root / f'{name}.reftrace.{index}.f32be',dtype='>f4').astype(np.float64)
        d=np.fromfile(root / f'{name}.dltrace.{variable}.f32be',dtype='>f4').astype(np.float64)
        assert r.shape==d.shape
        assert np.isfinite(r).all() and np.isfinite(d).all()
        error=np.abs(r-d)
        rounded=r.astype(np.float16).astype(np.float64)
        row={'variable':variable,'referenceIndex':index,'elements':len(r),'maxAbs':float(error.max()),
             'meanAbs':float(error.mean()),'relativeL2':float(np.linalg.norm(r-d)/np.linalg.norm(r)),
             'maxAbsAfterReferenceHalfRounding':float(np.max(np.abs(rounded-d))),
             'differing':int(np.count_nonzero(r!=d))}
        rows.append(row); print(json.dumps(row),flush=True)
    (root / (name+'.trace-comparison.json')).write_text(json.dumps(rows,indent=2))
else:
    reference = json.loads((root / (name+'.reference.json')).read_text())
    assert hashes == reference['inputSha256'], 'Frozen input changed'
    dlmeta = json.loads((root / (name+'.dl4j.json')).read_text())
    assert hashes == dlmeta['inputSha256'], 'DL4J scored another prompt'
    assert reference['capacity'] == dlmeta['capacity']
    import llama_cpp
    vocabulary = llama_cpp.Llama(model_path=a.model, vocab_only=True, verbose=False)
    rows=[]
    for step in range(len(reference['steps'])):
        r=np.fromfile(root / f'{name}.reference.{step}.f32be',dtype='>f4').astype(np.float64)
        d=np.fromfile(root / f'{name}.dl4j.{step}.f32be',dtype='>f4').astype(np.float64)
        assert r.shape==d.shape
        finite=np.isfinite(r)&np.isfinite(d)
        rt=np.argsort(-r,kind='stable')[:10]; dt=np.argsort(-d,kind='stable')[:10]
        err=np.abs(r[finite]-d[finite]); rel=err/np.maximum(np.abs(r[finite]),1e-6)
        row={'step':step,'vocab':len(r),'referenceFinite':int(np.isfinite(r).sum()),'dl4jFinite':int(np.isfinite(d).sum()),
             'maxAbs':float(err.max()),'meanAbs':float(err.mean()),'rms':float(np.sqrt(np.mean(err**2))),
             'maxRelativeFloor1e_6':float(rel.max()),'meanRelativeFloor1e_6':float(rel.mean()),
             'relativeL2':float(np.linalg.norm(d[finite]-r[finite])/np.linalg.norm(r[finite])),
             'referenceTop10':[(int(i),float(r[i])) for i in rt], 'dl4jTop10':[(int(i),float(d[i])) for i in dt],
             'top10Overlap':len(set(rt)&set(dt)), 'argmaxMatch':bool(rt[0]==dt[0]),
             'referenceMargin':float(r[rt[0]]-r[rt[1]]),'dl4jMargin':float(d[dt[0]]-d[dt[1]])}
        row['referencePiece'] = reference['steps'][step].get('piece')
        row['dl4jPiece'] = vocabulary.detokenize([int(dt[0])], special=True).decode('utf-8', errors='replace')
        row['topCandidatePieces'] = {str(int(i)): vocabulary.detokenize([int(i)], special=True).decode('utf-8', errors='replace')
                                     for i in set(rt[:3]) | set(dt[:3])}
        row['referenceWinnerDelta'] = float(d[rt[0]]-r[rt[0]])
        row['dl4jWinnerDelta'] = float(d[dt[0]]-r[dt[0]])
        rows.append(row)
        print('COMPARE_STEP', json.dumps({key:row[key] for key in ('step','referencePiece','dl4jPiece','argmaxMatch','referenceMargin','dl4jMargin','referenceWinnerDelta','dl4jWinnerDelta')}), flush=True)
    vocabulary.close()
    (root / (name+'.comparison.json')).write_text(json.dumps(rows,indent=2))
    print('FIRST_DIVERGENCE', next((row for row in rows if not row['argmaxMatch']), None), flush=True)
