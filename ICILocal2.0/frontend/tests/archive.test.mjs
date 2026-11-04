import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';

test('archived prototype matches captured hashes and root demo', async () => {
  const root = new URL('../../../',import.meta.url);
  const archive = new URL('archive/high-fidelity-2026-11-02/',root);
  const manifest = JSON.parse(await readFile(new URL('MANIFEST.json',archive),'utf8'));
  assert.ok(manifest.files.length > 40);
  for (const file of manifest.files) {
    const bytes=await readFile(new URL(file.path,archive));
    assert.equal(createHash('sha256').update(bytes).digest('hex'),file.sha256,file.path);
    assert.equal(bytes.length,file.bytes);
    if (file.path !== 'README.md') assert.deepEqual(bytes,await readFile(new URL('prototype/'+file.path,root)),file.path);
  }
});
