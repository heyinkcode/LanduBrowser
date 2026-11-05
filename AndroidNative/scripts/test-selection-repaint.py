#!/usr/bin/env python3
"""Test the staged PaintChunker endpoint updates with repeated paints."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, required=True)
parser.add_argument('--output-parent', type=Path, required=True)
args = parser.parse_args()
native = Path(__file__).resolve().parent.parent
target = 'third_party/blink/renderer/platform/graphics/paint/paint_chunker.cc'
lock = json.loads((native / 'upstream-lock.json').read_text())
node = args.source / 'third_party/node/linux/node-linux-x64/bin/node'
script = '''
import fs from 'node:fs';
import {pathToFileURL} from 'node:url';
const [native, source, target, hash] = process.argv.slice(1);
const {preparePatchedFile} = await import(pathToFileURL(native+'/scripts/overlay-plan.mjs'));
const result = preparePatchedFile(target, fs.readFileSync(source+'/'+target), hash);
const repeat = preparePatchedFile(target, Buffer.from(result.content), hash);
if (!repeat.alreadyPatched || repeat.content !== result.content) throw Error('Patch roundtrip failed');
process.stdout.write(result.content);
'''
staged = subprocess.check_output([str(node), '--input-type=module', '-e', script,
    str(native), str(args.source), target, lock['files'][target]], text=True)
start = staged.index('  LayerSelectionData& selection_data', staged.index('void PaintChunker::AddSelectionToCurrentChunk('))
end = staged.index('\n}\n', start)
body = staged[start:end]
assert 'DCHECK' not in body
program = r'''
#include <cassert>
#include <optional>
struct Bound {
  int type;
  int x;
  int y;
  bool operator==(const Bound&) const = default;
};
struct LayerSelectionData {
  std::optional<Bound> start;
  std::optional<Bound> end;
  bool any_selection_was_painted = true;
};
struct Chunk {
  LayerSelectionData data;
  LayerSelectionData& EnsureLayerSelectionData() { return data; }
};
void Record(Chunk& chunk, std::optional<Bound> start, std::optional<Bound> end) {
BODY
}
int main() {
  const Bound old_start{0, 8, 10}, old_end{1, 90, 10};
  const Bound new_start{1, 12, 30}, new_end{0, 110, 60};
  for (unsigned mask = 0; mask < 16; ++mask) {
    Chunk chunk;
    if (mask & 1) chunk.data.start = old_start;
    if (mask & 2) chunk.data.end = old_end;
    auto start = (mask & 4) ? std::optional(new_start) : std::nullopt;
    auto end = (mask & 8) ? std::optional(new_end) : std::nullopt;
    const auto expected_start = start ? start : chunk.data.start;
    const auto expected_end = end ? end : chunk.data.end;
    Record(chunk, start, end);
    assert(chunk.data.start == expected_start);
    assert(chunk.data.end == expected_end);
    assert(chunk.data.any_selection_was_painted);
    // An identical repaint must be idempotent, including an absent endpoint.
    Record(chunk, start, end);
    assert(chunk.data.start == expected_start);
    assert(chunk.data.end == expected_end);
  }
}
'''.replace('BODY', body)
args.output_parent.mkdir(parents=True, exist_ok=True)
output = Path(tempfile.mkdtemp(prefix='selection-repaint-', dir=args.output_parent))
(output / 'paint_chunker.cc').write_text(staged)
(output / 'selection-test.cc').write_text(program)
subprocess.run(['c++', '-std=c++20', '-Wall', '-Wextra', '-Werror',
    '-fsanitize=address,undefined', '-fno-omit-frame-pointer',
    str(output / 'selection-test.cc'), '-o', str(output / 'selection-test')], check=True)
subprocess.run([str(output / 'selection-test')], check=True)
print(json.dumps({'passed': True, 'behaviorCases': 32, 'patchRoundtripPassed': True,
    'sanitizers': ['address', 'undefined'], 'output': str(output),
    'patchedSha256': hashlib.sha256(staged.encode()).hexdigest(),
    'apkBuilt': False, 'deviceTested': False}))
