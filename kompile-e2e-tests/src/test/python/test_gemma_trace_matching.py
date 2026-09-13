"""Weights-free fail-closed tests for named, shape- and position-matched traces."""
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
import numpy as np

SCRIPT = pathlib.Path(__file__).with_name('gemma_independent_reference.py')
NAMES = [('embedded','embd'), ('embed_scaled','inp_scaled'), ('attn_norm_0','attn_norm-0'),
         ('q_proj_0','Qcur-0'), ('q_norm_0','Qcur_normed-0'), ('q_rope_0','Qcur_pos-0')]

class TraceMatchingTest(unittest.TestCase):
    def run_fixture(self, mutation=None):
        with tempfile.TemporaryDirectory(prefix='gemma-trace-selftest-') as directory:
            root = pathlib.Path(directory)
            (root/'case.ids.json').write_text(json.dumps(list(range(743))))
            (root/'case.prompt.txt').write_text('synthetic fixture, no tokenizer or model')
            hashes = {s:hashlib.sha256((root/('case.'+s)).read_bytes()).hexdigest()
                      for s in ('ids.json','prompt.txt')}
            positions = [0,1,511,512,742]
            ref, dl = [], []
            for index, (variable,name) in enumerate(NAMES):
                # Deliberately non-positional reference indices; two microbatches versus one.
                channels = [8,256] if variable in ('q_norm_0','q_rope_0') else [1536 if index<3 else 2048]
                width = int(np.prod(channels))
                data = (np.arange(len(positions)*width,dtype=np.float32) % 31).reshape(len(positions),width)
                filename = variable+'.f32be'; data.astype('>f4').tofile(root/filename)
                dl.append(dict(variable=variable, shape=[1,743]+channels, dtype='HALF',
                               positions=positions, width=width, file=filename))
                for start,count,selected in [(0,512,positions[:3]),(512,231,positions[3:])]:
                    filename = f'{variable}-{start}.f32be'
                    data[[positions.index(p) for p in selected]].astype('>f4').tofile(root/filename)
                    ref.append(dict(index=90-index,name=name,variable=variable,
                                    shape=list(reversed(channels))+[count]+[1]*(3-len(channels)),
                                    tokenAxis=len(channels),batchStart=start,batchTokens=count,
                                    positions=selected,width=width,file=filename,dtype='F32'))
            ref.reverse()
            if mutation: mutation(ref,dl)
            (root/'case.reftrace.json').write_text(json.dumps(dict(inputSha256=hashes,positions=positions,tensors=ref)))
            (root/'case.dltrace.json').write_text(json.dumps(dict(inputSha256=hashes,tensors=dl)))
            result = subprocess.run([sys.executable,str(SCRIPT),'comparetrace','--root',directory,
                                     '--trace-output',directory,'--case','case','--timeout','20'],
                                    capture_output=True,text=True,timeout=25)
            if result.returncode == 0:
                rows = json.loads((root/'case.trace-comparison.json').read_text())
                self.assertEqual(6,len(rows))
                self.assertTrue(all(row['float32BitDiffering']==0 for row in rows))
            return result

    def test_shuffled_names_and_microbatch_positions_match(self):
        result=self.run_fixture(); self.assertEqual(0,result.returncode,result.stderr)

    def test_wrong_name_rejected(self):
        self.assertNotEqual(0,self.run_fixture(lambda r,d:r[0].update(name='wrong')).returncode)

    def test_equal_width_wrong_head_shape_rejected(self):
        self.assertNotEqual(0,self.run_fixture(lambda r,d:r[0].update(shape=[128,16,231,1])).returncode)

    def test_duplicate_or_missing_global_position_rejected(self):
        self.assertNotEqual(0,self.run_fixture(lambda r,d:r[0].update(positions=[511,742])).returncode)

if __name__ == '__main__':
    unittest.main()
