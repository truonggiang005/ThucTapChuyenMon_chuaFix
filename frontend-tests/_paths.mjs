// Đường dẫn tới mã frontend (ES module thuần, không cần trình duyệt/bundler).
import { pathToFileURL } from 'node:url';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const JS_ROOT = path.resolve(here, '../src/main/resources/static/js');
export const load = (rel) => import(pathToFileURL(path.join(JS_ROOT, rel)).href);
export const RESOURCES = path.resolve(here, '../src/main/resources');
