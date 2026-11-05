#!/usr/bin/env python3
"""Compile the real staged Dawn message filter and test the captured driver warning."""
import argparse
import hashlib
import json
import shutil
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, required=True)
parser.add_argument('--output-parent', type=Path, required=True)
parser.add_argument('--cxx', default='c++')
parser.add_argument('--node')
args = parser.parse_args()
native = Path(__file__).resolve().parent.parent
target = 'third_party/dawn/src/dawn/native/vulkan/BackendVk.cpp'
lock = json.loads((native/'upstream-lock.json').read_text())
code = '''
import fs from 'node:fs';
import {pathToFileURL} from 'node:url';
const [native, source, target, sha] = process.argv.slice(1);
const {preparePatchedFile} = await import(pathToFileURL(native+'/scripts/overlay-plan.mjs'));
const original = fs.readFileSync(source+'/'+target);
const staged = preparePatchedFile(target, original, sha);
const roundtrip = preparePatchedFile(target, Buffer.from(staged.content), sha);
if (!roundtrip.alreadyPatched || roundtrip.content !== staged.content) throw new Error('Patch roundtrip failed');
process.stdout.write(staged.content);
'''
node = args.node or shutil.which('node') or shutil.which('nodejs')
if not node:
    node = str(args.source/'third_party/node/linux/node-linux-x64/bin/node')
staged = subprocess.check_output([node, '--input-type=module', '-e', code,
                                 str(native), str(args.source), target, lock['files'][target]], text=True)
table = staged[staged.index('struct SkippedMessage {'):staged.index('namespace dawn::native::vulkan {')]
function = staged[staged.index('bool ShouldReportDebugMessage('):staged.index('void LogCallbackData(')]
tests = r'''
#include <cassert>
#include <string>
int main() {
  const char* known = "The following warning was triggered: VKDBGUTILWARN003. Please refer to the "
      "Adreno Game Developer Guide for more information: "
      "https://developer.qualcomm.com/docs/adreno-gpu/developer-guide/index.html";
  assert(!ShouldReportDebugMessage(nullptr, known));
  assert(!ShouldReportDebugMessage("Adreno", known));
  assert(!ShouldReportDebugMessage(nullptr, nullptr));
  assert(!ShouldReportDebugMessage(nullptr, "VK_SUCCESS"));
  assert(ShouldReportDebugMessage(nullptr, "actual driver failure"));
  assert(ShouldReportDebugMessage("VUID-vkCmdDraw-None-99999", "invalid resource access"));
  assert(ShouldReportDebugMessage(nullptr, (std::string(known)+" additional validation failure").c_str()));
  assert(ShouldReportDebugMessage(nullptr, "The following warning was triggered: VKDBGUTILWARN004."));
  assert(ShouldReportDebugMessage("VKDBGUTILWARN003", "invalid memory access"));
  assert(ShouldReportDebugMessage(nullptr, "The following warning was triggered: VKDBGUTILWARN003."));
  assert(!ShouldReportDebugMessage("NVIDIA", "vkBindImageMemory: memoryTypeIndex"));
  return 0;
}
'''
args.output_parent.mkdir(parents=True, exist_ok=True)
out = Path(tempfile.mkdtemp(prefix='vulkan-warning-', dir=args.output_parent))
(out/'BackendVk.cpp').write_text(staged)
(out/'filter-test.cc').write_text('#include <string_view>\n'+table+function+tests)
subprocess.run([args.cxx, '-std=c++20', '-Wall', '-Wextra', '-Werror',
                str(out/'filter-test.cc'), '-o', str(out/'filter-test')], check=True)
subprocess.run([str(out/'filter-test')], check=True)
print(json.dumps({'passed': True, 'behaviorCases': 11, 'patchRoundtripPassed': True,
                  'patchedSha256': hashlib.sha256(staged.encode()).hexdigest(),
                  'output': str(out), 'apkBuilt': False, 'deviceTested': False}))
