// Copies non-TypeScript runtime assets (the injected page script) into dist/.
import { cpSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const source = join(root, 'src', 'inject');
if (existsSync(source)) {
  mkdirSync(join(root, 'dist', 'inject'), { recursive: true });
  cpSync(source, join(root, 'dist', 'inject'), { recursive: true });
}
