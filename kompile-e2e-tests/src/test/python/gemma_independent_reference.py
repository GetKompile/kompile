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
p.add_argument('mode', choices=['reference', 'compare', 'trace', 'comparetrace', 'embeddingoracle', 'scaleoracle'])
p.add_argument('--root', required=True)
p.add_argument('--case', default='short')
p.add_argument('--model')
p.add_argument('--isolated-root')
p.add_argument('--trace-output', help='Fresh trace directory; frozen input root remains read-only')
p.add_argument('--check-only', action='store_true', help='Validate existing trace comparison without rewriting artifacts')
p.add_argument('--freeze-source', help='Existing template parity JSON; never regenerate a request')
p.add_argument('--teacher-force', help='Exported saved response IDs; score identical canonical response prefixes, not free generation')
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
teacher = json.loads(pathlib.Path(a.teacher_force).read_text()) if a.teacher_force else None
if teacher is not None:
    assert a.mode == 'reference' and 0 < len(teacher) <= a.steps
    a.steps = len(teacher)
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
        assert a.trace_output
        trace_root = pathlib.Path(a.trace_output)
        trace_root.mkdir(parents=True, exist_ok=True)
        assert not list(trace_root.glob(name + '.reftrace.*')), 'Never overwrite trace artifacts'
        trace_positions = sorted(set(x for x in (0, 1, 511, 512, len(ids)-1) if x < len(ids)))
        aliases = {'embd':'embedded', 'inp_scaled':'embed_scaled', 'attn_norm-0':'attn_norm_0',
                   'Qcur-0':'q_proj_0', 'Qcur_normed-0':'q_norm_0', 'Qcur_pos-0':'q_rope_0'}
        batch_start, batch_tokens = 0, 0
        callback_errors = []
        import ctypes as c
        get_name = native._lib.ggml_get_name; get_name.argtypes=[c.c_void_p]; get_name.restype=c.c_char_p
        elements = native._lib.ggml_nelements; elements.argtypes=[c.c_void_p]; elements.restype=c.c_int64
        nbytes = native._lib.ggml_nbytes; nbytes.argtypes=[c.c_void_p]; nbytes.restype=c.c_size_t
        get = native._lib.ggml_backend_tensor_get
        get.argtypes=[c.c_void_p,c.c_void_p,c.c_size_t,c.c_size_t]; get.restype=None
        # Public GGUF descriptor APIs expose actual type/shape without guessing ggml_tensor ABI.
        def bind(symbol, args, result):
            f = getattr(native._lib, symbol); f.argtypes = args; f.restype = result; return f
        empty = bind('gguf_init_empty', [], c.c_void_p)
        add = bind('gguf_add_tensor', [c.c_void_p, c.c_void_p], None)
        dim = bind('gguf_get_tensor_ne', [c.c_void_p, c.c_int64], c.POINTER(c.c_int64))
        dtype = bind('gguf_get_tensor_type', [c.c_void_p, c.c_int64], c.c_int)
        free = bind('gguf_free', [c.c_void_p], None)
        contiguous = bind('ggml_is_contiguous', [c.c_void_p], c.c_bool)
        @native.ggml_backend_sched_eval_callback
        def callback(t, ask, data):
            global batch_start, batch_tokens
            label = get_name(t).decode()
            if ask: return label in aliases
            if label not in aliases: return True
            try:
                descriptor = empty()
                try:
                    add(descriptor, t)
                    shape = list(dim(descriptor, 0)[:4])
                    actual_type = dtype(descriptor, 0)
                finally: free(descriptor)
                assert actual_type == native.GGML_TYPE_F32, (label, actual_type)
                assert contiguous(t), (label, 'noncontiguous: no unsafe flat read')
                token_axis = 2 if label in ('Qcur_normed-0', 'Qcur_pos-0') else 1
                assert all(v == 1 for v in shape[token_axis+1:]), (label, shape)
                width = int(np.prod(shape[:token_axis])); tokens = shape[token_axis]
                assert int(np.prod(shape)) == elements(t)
                if label == 'embd':
                    batch_start += batch_tokens; batch_tokens = tokens
                assert tokens == batch_tokens and batch_start + tokens <= len(ids), (label, shape, batch_start)
                positions = [v for v in trace_positions if batch_start <= v < batch_start + tokens]
                values = []
                for pos in positions:
                    buf = (c.c_float * width)()
                    get(t, buf, (pos - batch_start) * width * 4, width * 4)
                    values.append(np.ctypeslib.as_array(buf).copy())
                index = len(captured)
                array = np.asarray(values, dtype=np.float32).reshape(len(positions), width)
                filename = f'{name}.reftrace.{index}.f32be'
                with (trace_root / filename).open('xb') as out: array.astype('>f4').tofile(out)
                captured.append({'index':index, 'name':label, 'variable':aliases[label], 'shape':shape,
                                 'dtype':'F32', 'tokenAxis':token_axis, 'positions':positions,
                                 'batchStart':batch_start, 'batchTokens':tokens, 'width':width,
                                 'file':filename, 'finite':int(np.isfinite(array).sum())})
            except Exception as error:
                callback_errors.append(repr(error))
                print('TRACE_CALLBACK_ERROR', repr(error), flush=True)
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
    if teacher is not None:
        own_response_ids = model.tokenize((root / (name + '.response.txt')).read_bytes(), add_bos=False, special=True)
        assert own_response_ids == teacher, 'Saved-response tokenizer discrepancy: stop before inference'
        print('REFERENCE_RESPONSE_TOKENIZATION', len(teacher), 'exact', flush=True)
    if a.mode == 'trace':
        try:
            model.eval(ids)
            assert not callback_errors, callback_errors
            assert batch_start + batch_tokens == len(ids)
            for variable in aliases.values():
                assert sorted(p for row in captured if row['variable'] == variable for p in row['positions']) == trace_positions
            with (trace_root / (name+'.reftrace.json')).open('x') as out:
                json.dump({'inputSha256':hashes, 'positions':trace_positions, 'tensors':captured,
                           'n_batch':min(capacity,512), 'n_ubatch':min(capacity,512)}, out, indent=2)
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
    if teacher is not None:
        metadata['teacherForceSource'] = a.teacher_force
        metadata['teacherForceSha256'] = hashlib.sha256(pathlib.Path(a.teacher_force).read_bytes()).hexdigest()
        metadata['qualificationLimit'] = 'Canonical tokenization of saved response text; no captured generation IDs, masks or sampling parity. Raw logits only.'
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
            forced.append(int(top[0]) if teacher is None else teacher[step])
            record['rawArgmaxId'] = int(top[0])
            record['scoredResponseId'] = forced[-1]
            record['rawArgmaxMatchesResponse'] = int(top[0]) == forced[-1]
            metadata['continuation'] = model.detokenize(forced, special=True).decode('utf-8', errors='replace')
            record['piece'] = model.detokenize([forced[-1]], special=True).decode('utf-8', errors='replace')
            if teacher is None and record['piece'] in ('<tool_call|>', '<tool|>', '<turn|>', '<|turn>', '<eos>'):
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
elif a.mode == 'scaleoracle':
    trace_root=pathlib.Path(a.trace_output)
    dl=json.loads((trace_root/(name+'.dltrace.json')).read_text())
    assert dl['inputSha256']==hashes
    records={t['variable']:t for t in dl['tensors']}
    embedded=records['embedded']; scaled=records['embed_scaled']
    assert embedded['shape']==scaled['shape'] and embedded['positions']==scaled['positions']
    x=np.fromfile(trace_root/embedded['file'],dtype='>f4').astype(np.float32)
    actual=np.fromfile(trace_root/scaled['file'],dtype='>f4').astype(np.float32)
    scale=np.float32(np.sqrt(embedded['width']))
    rows=[]
    for label, scalar in [('float32Scalar',scale),('halfScalar',np.float32(np.float16(scale)))]:
        expected=(x*scalar).astype(np.float16).astype(np.float32)
        err=np.abs(expected-actual)
        rows.append({'input':'identical captured HALF embedded values', 'scalarContract':label,'scalar':float(scalar),
                     'outputDtype':'HALF','elements':len(x),'maxAbs':float(err.max()),'rms':float(np.sqrt(np.mean(err.astype(np.float64)**2))),
                     'float32BitDiffering':int(np.count_nonzero(expected.view(np.uint32)!=actual.view(np.uint32)))})
    with (trace_root/(name+'.scale-oracle.json')).open('x') as out: json.dump(rows,out,indent=2)
    print('SCALE_ORACLE',json.dumps(rows),flush=True)
elif a.mode == 'embeddingoracle':
    # Read only five GGUF rows. This oracle does not create a llama context or execute a graph.
    import ctypes as c
    import llama_cpp.llama_cpp as native
    trace_root = pathlib.Path(a.trace_output)
    def bind(symbol, args, result):
        f = getattr(native._lib, symbol); f.argtypes=args; f.restype=result; return f
    class Params(c.Structure):
        _fields_=[('no_alloc', c.c_bool), ('ctx', c.c_void_p)]
    init=bind('gguf_init_from_file',[c.c_char_p, Params],c.c_void_p)
    free=bind('gguf_free',[c.c_void_p],None)
    find=bind('gguf_find_tensor',[c.c_void_p,c.c_char_p],c.c_int64)
    shape_fn=bind('gguf_get_tensor_ne',[c.c_void_p,c.c_int64],c.POINTER(c.c_int64))
    type_fn=bind('gguf_get_tensor_type',[c.c_void_p,c.c_int64],c.c_int)
    type_name=bind('ggml_type_name',[c.c_int],c.c_char_p)
    row_size=bind('ggml_row_size',[c.c_int,c.c_int64],c.c_size_t)
    offset_fn=bind('gguf_get_tensor_offset',[c.c_void_p,c.c_int64],c.c_size_t)
    data_fn=bind('gguf_get_data_offset',[c.c_void_p],c.c_size_t)
    ctx=init(os.fsencode(a.model),Params(True,None)); assert ctx
    try:
        idx=find(ctx,b'token_embd.weight'); assert idx>=0
        shape=list(shape_fn(ctx,idx)[:4]); dtype=type_fn(ctx,idx); typename=type_name(dtype).decode()
        width=shape[0]; size=row_size(dtype,width); offset=data_fn(ctx)+offset_fn(ctx,idx)
    finally: free(ctx)
    ref=json.loads((trace_root/(name+'.reftrace.json')).read_text())
    dl=json.loads((trace_root/(name+'.dltrace.json')).read_text())
    assert ref['inputSha256']==dl['inputSha256']==hashes
    target=next(t for t in dl['tensors'] if t['variable']=='embedded')
    assert target['width']==width and target['dtype']=='HALF'
    positions=target['positions']; output=[]
    with open(a.model,'rb') as weights:
        for pos in positions:
            token=ids[pos]; assert 0<=token<shape[1]
            weights.seek(offset+token*size); raw=weights.read(size); assert len(raw)==size
            if dtype==native.GGML_TYPE_F32: values=np.frombuffer(raw,dtype='<f4').copy()
            elif dtype==native.GGML_TYPE_F16: values=np.frombuffer(raw,dtype='<f2').astype(np.float32)
            else:
                dequant=bind('dequantize_row_'+typename,[c.c_void_p,c.POINTER(c.c_float),c.c_int64],None)
                buf=(c.c_float*width)(); packed=c.create_string_buffer(raw)
                dequant(packed,buf,width); values=np.ctypeslib.as_array(buf).copy()
            output.append(values)
    oracle=np.stack(output)
    actual=np.fromfile(trace_root/target['file'],dtype='>f4').reshape(oracle.shape)
    rounded=oracle.astype(np.float16).astype(np.float32)
    refrows={}
    for t in ref['tensors']:
        if t['variable']=='embedded':
            values=np.fromfile(trace_root/t['file'],dtype='>f4').reshape(len(t['positions']),width)
            refrows.update(zip(t['positions'],values))
    reference=np.stack([refrows[p] for p in positions])
    result={'tensor':'token_embd.weight','GGUFtype':typename,'GGUFshape':shape,'rowBytes':size,
            'GGUFpath':a.model,'GGUFbytes':os.stat(a.model).st_size,'positions':positions,'tokenIds':[ids[p] for p in positions],
            'SDZactivationDtype':target['dtype'], 'oracleVsReferenceBitDiffering':int(np.count_nonzero(oracle.view(np.uint32)!=reference.astype(np.float32).view(np.uint32))),
            'roundedHalfVsSDZBitDiffering':int(np.count_nonzero(rounded.view(np.uint32)!=actual.astype(np.float32).view(np.uint32))),
            'roundedHalfVsSDZMaxAbs':float(np.max(np.abs(rounded-actual))),
            'rawVsSDZMaxAbs':float(np.max(np.abs(oracle-actual)))}
    with (trace_root/(name+'.embedding-oracle.json')).open('x') as out: json.dump(result,out,indent=2)
    print('EMBEDDING_ORACLE',json.dumps(result),flush=True)
elif a.mode == 'comparetrace':
    trace_root = pathlib.Path(a.trace_output)
    ref = json.loads((trace_root / (name+'.reftrace.json')).read_text())
    dl = json.loads((trace_root / (name+'.dltrace.json')).read_text())
    assert ref['inputSha256'] == dl['inputSha256'] == hashes
    rows=[]
    for variable in ('embedded','embed_scaled','attn_norm_0','q_proj_0','q_norm_0','q_rope_0'):
        target = next(t for t in dl['tensors'] if t['variable'] == variable)
        matches = [t for t in ref['tensors'] if t['variable'] == variable]
        samples = {}
        expected_name={'embedded':'embd','embed_scaled':'inp_scaled','attn_norm_0':'attn_norm-0',
                       'q_proj_0':'Qcur-0','q_norm_0':'Qcur_normed-0','q_rope_0':'Qcur_pos-0'}[variable]
        for t in matches:
            assert t['name'] == expected_name, (t, expected_name)
            assert list(reversed(t['shape'][:t['tokenAxis']])) == target['shape'][2:], (t, target)
            assert t['shape'][t['tokenAxis']] == t['batchTokens']
            assert all(t['batchStart'] <= p < t['batchStart'] + t['batchTokens'] for p in t['positions'])
            assert target['shape'][:2] == [1,len(ids)]
            assert t['width'] == target['width'], (t, target)
            data = np.fromfile(trace_root / t['file'], dtype='>f4').reshape(len(t['positions']), t['width'])
            for pos, values in zip(t['positions'], data):
                assert pos not in samples, (variable, pos)
                samples[pos] = values
        assert sorted(samples) == target['positions'] == ref['positions']
        r=np.stack([samples[p] for p in target['positions']]).astype(np.float64)
        d=np.fromfile(trace_root / target['file'],dtype='>f4').reshape(r.shape).astype(np.float64)
        assert np.isfinite(r).all() and np.isfinite(d).all()
        error=np.abs(r-d); rounded=r.astype(np.float16).astype(np.float64)
        loc=np.unravel_index(np.argmax(error),error.shape)
        row={'variable':variable,'referenceNames':[t['name'] for t in matches],
             'referenceShapes':[t['shape'] for t in matches], 'dl4jShape':target['shape'],
             'referenceDtype':'F32','dl4jDtype':target['dtype'], 'positions':target['positions'],
             'elements':int(r.size),'maxAbs':float(error.max()), 'rms':float(np.sqrt(np.mean(error**2))),
             'meanAbs':float(error.mean()),'relativeL2':float(np.linalg.norm(r-d)/np.linalg.norm(r)),
             'maxAbsAfterReferenceHalfRounding':float(np.max(np.abs(rounded-d))),
             'halfRoundedDiffering':int(np.count_nonzero(rounded!=d)),
             'float32BitDiffering':int(np.count_nonzero(r.astype(np.float32).view(np.uint32)!=d.astype(np.float32).view(np.uint32))),
             'worst':{'position':target['positions'][loc[0]], 'channel':int(loc[1]), 'reference':float(r[loc]), 'dl4j':float(d[loc])},
             'differing':int(np.count_nonzero(r!=d))}
        rows.append(row); print(json.dumps(row),flush=True)
    if not a.check_only:
        with (trace_root / (name+'.trace-comparison.json')).open('x') as out: json.dump(rows,out,indent=2)
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
    response_path = root / (name+'.response.ids.json')
    if response_path.exists():
        response_ids = json.loads(response_path.read_text())
        decision_rows = []
        for step, token in enumerate(response_ids[:len(rows)]):
            prefix = vocabulary.detokenize(response_ids[:step], special=True).decode('utf-8')
            if prefix.endswith('comparedTypeRelation:<|"|>'):
                for backend in ('reference', 'dl4j'):
                    scores = np.fromfile(root / f'{name}.{backend}.{step}.f32be', dtype='>f4').astype(np.float64)
                    winner = int(np.argmax(scores))
                    alternatives = {}
                    for label in ('EQUIVALENT', 'PROPER_SUBTYPE', 'INCOMPATIBLE', 'UNKNOWN'):
                        first = vocabulary.tokenize(label.encode(), add_bos=False, special=True)[0]
                        alternatives[label] = {'firstToken':first, 'piece':vocabulary.detokenize([first], special=True).decode('utf-8'),
                            'logit':float(scores[first]), 'rank':int(np.sum(scores > scores[first]))+1,
                            'winnerGap':float(scores[winner]-scores[first])}
                    decision_rows.append({'step':step, 'backend':backend, 'prefix':prefix, 'rawWinner':winner,
                        'rawWinnerPiece':vocabulary.detokenize([winner], special=True).decode('utf-8'), 'alternatives':alternatives})
        (root / (name+'.raw-decision.json')).write_text(json.dumps(decision_rows,indent=2))
        print('RAW_DECISION', json.dumps(decision_rows), flush=True)
    summary = {'checkpoints':len(rows), 'argmaxMatches':sum(row['argmaxMatch'] for row in rows),
               'allFinite':all(row['referenceFinite']==row['vocab']==row['dl4jFinite'] for row in rows),
               'maxAbsRange':[min(row['maxAbs'] for row in rows),max(row['maxAbs'] for row in rows)],
               'relativeL2Range':[min(row['relativeL2'] for row in rows),max(row['relativeL2'] for row in rows)],
               'referenceMarginRange':[min(row['referenceMargin'] for row in rows),max(row['referenceMargin'] for row in rows)],
               'dl4jMarginRange':[min(row['dl4jMargin'] for row in rows),max(row['dl4jMargin'] for row in rows)],
               'constraintParity':'Not applied: raw reference replay; first-token ranks are not complete enum sequence scores.'}
    (root / (name+'.comparison-summary.json')).write_text(json.dumps(summary,indent=2))
    print('COMPARISON_SUMMARY', json.dumps(summary), flush=True)
    vocabulary.close()
    (root / (name+'.comparison.json')).write_text(json.dumps(rows,indent=2))
    print('FIRST_DIVERGENCE', next((row for row in rows if not row['argmaxMatch']), None), flush=True)
