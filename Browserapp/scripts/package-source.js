const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const { isPackagingExcluded } = require('./package-portable');

const repository = path.resolve(__dirname, '../..');
const version = require('../package.json').version;
const suffix = String(process.env.OPENBROWSER_SOURCE_SUFFIX || '').trim();
if (suffix && !/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}$/.test(suffix)) throw new Error('Use a single source suffix, without paths');
const stem = `LanduBrowser-${version}${suffix ? '-' + suffix : ''}-source`;
const destination = path.join(repository, 'Browserapp', 'dist', stem);
if (fs.existsSync(destination)) throw new Error('Source staging already exists: ' + destination);
const files = execFileSync('git', ['-c', `safe.directory=${repository.replaceAll('\\', '/')}`, '-c', 'filter.lfs.required=false', '-c', 'filter.lfs.process=', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], { cwd: repository, encoding: 'utf8' }).split('\0').filter(Boolean);
const manifest = [];
for (const relative of [...new Set(files)].sort()) {
  const segments = relative.split('/');
  if (segments.some(segment => isPackagingExcluded(segment) || ['dist', 'kernels', 'bundled-kernels'].includes(segment.toLowerCase()))) continue;
  if (segments.some(segment => ['.local', '.gradle', '.kotlin', 'build', 'captures'].includes(segment.toLowerCase()))) continue;
  if (segments.some(segment => segment.toLowerCase() === 'agents.md') || relative === 'SOURCE_MANIFEST.sha256') continue;
  if (relative.startsWith('AndroidNative/validation/') || relative === 'AndroidNative/feature-parity.json') continue;
  if (relative.startsWith('Androidapp/docs/') || relative.startsWith('.github/workflows/')) continue;
  if (/\.(exe|dll|dylib|so|zip|7z|dmg|tar|gz|apk|aab|jks|keystore|class|dex)$/i.test(relative)) continue;
  const source = path.join(repository, relative);
  if (!fs.existsSync(source)) continue;
  if (!fs.lstatSync(source).isFile()) throw new Error('Unexpected non-file in source package: ' + relative);
  const output = path.join(destination, relative);
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.copyFileSync(source, output);
  manifest.push(crypto.createHash('sha256').update(fs.readFileSync(source)).digest('hex') + '  ' + relative);
}
for (const required of ['BUILD_LANDU.md', 'README_LANDU.md', 'LICENSE', 'Browserapp/main.js', 'Browserapp/landu-apple.css', 'Browserapp/native-input-mirror.cs', 'Browserapp/LICENSE.chrome-power.txt']) {
  if (!fs.existsSync(path.join(destination, required))) throw new Error('Missing source deliverable: ' + required);
}
fs.writeFileSync(path.join(destination, 'SOURCE_MANIFEST.sha256'), manifest.join('\n') + '\n');
const archive = destination + '.zip';
execFileSync('powershell', ['-NoProfile', '-NonInteractive', '-Command', `Add-Type -AssemblyName System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::CreateFromDirectory('${destination.replaceAll("'", "''")}', '${archive.replaceAll("'", "''")}', [System.IO.Compression.CompressionLevel]::Optimal, $true)`], { stdio: 'inherit' });
console.log(JSON.stringify({ archive, files: manifest.length, bytes: fs.statSync(archive).size }, null, 2));
