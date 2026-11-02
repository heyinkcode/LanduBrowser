import { rcedit } from 'rcedit';

const [exePath, iconPath] = process.argv.slice(2);
if (!exePath || !iconPath) {
  throw new Error('Usage: node brand-exe.mjs <exePath> <iconPath>');
}

await rcedit(exePath, {
  'version-string': {
    ProductName: 'Landu Browser',
    FileDescription: '岚渡浏览器 · Landu Browser',
    CompanyName: '岚渡浏览器开源项目',
    LegalCopyright: 'Based on OpenBrowser contributors; see bundled licenses',
    OriginalFilename: 'LanduBrowser.exe'
  },
  'file-version': '1.0.26.0',
  'product-version': '1.0.26.0',
  icon: iconPath,
  'requested-execution-level': 'asInvoker'
});
