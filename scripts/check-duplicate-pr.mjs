// Read-only checks against the exact commit reviewed in PR #6.
// Run from any directory: node scripts/check-duplicate-pr.mjs
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';

const root = fileURLToPath(new URL('../', import.meta.url));
const source = execFileSync('git', ['show', '7e0dc9f:prototype/assets/js/data-store.js'], { cwd: root, encoding: 'utf8' });
function fixture() {
  const storage = new Map();
  const context = vm.createContext({ window: {}, localStorage: {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
  }, document: { dispatchEvent() {} }, CustomEvent: class {}, location: { reload() {} } });
  vm.runInContext(source, context);
  const store = context.window.Store;
  store.addApplicant({ id: 'CHECK-EXISTING', name: 'Testfamily, Testgiven', last: 'Testfamily', first: 'Testgiven',
    dob: '03/09/2009', lrn: '999999999991', campusKey: 'bugo' });
  return store;
}
const candidate = { name: 'Testfamily, Testgiven', dob: '3/9/2009', lrn: '' };
test('exact LRN finds an applicant across campuses even with a different name and DOB', () => {
  const match = fixture().findDuplicates({ name: 'Different, Person', dob: '01/01/2001', lrn: '999999999991', campusKey: 'main' });
  assert.ok(match.some(item => item.id === 'CHECK-EXISTING' && item.type === 'LRN_EXACT'));
});
test('fuzzy name plus normalized DOB flags a suspected duplicate', () => {
  assert.ok(fixture().findDuplicates({ ...candidate, name: 'Testfamily, Testgivem' }).some(item => item.id === 'CHECK-EXISTING' && item.type === 'NAME_DOB'));
});
test('same name with a different DOB does not match', () => {
  assert.equal(fixture().findDuplicates({ ...candidate, dob: '03/10/2009' }).some(item => item.id === 'CHECK-EXISTING'), false);
});
test('empty identifiers do not match', () => {
  assert.equal(fixture().findDuplicates({ name: '', dob: '', lrn: '' }).length, 0);
});
test('an applicant is excluded from its own matches', () => {
  assert.equal(fixture().findDuplicates({ ...candidate, lrn: '999999999991' }, 'CHECK-EXISTING').some(item => item.id === 'CHECK-EXISTING'), false);
});
test('duplicate check flags and creates a comparison pair; resolved pairs stay resolved', () => {
  const store = fixture();
  store.addApplicant({ ...candidate, id: 'CHECK-INCOMING', campusKey: 'main' });
  assert.equal(store.runDuplicateCheck('CHECK-INCOMING').flagged, true);
  assert.equal(store.applicants['CHECK-INCOMING'].flag, 'dup');
  assert.ok(store.getPair('CHECK-INCOMING'));
  store.resolvePair('CHECK-INCOMING', 'reject');
  assert.equal(store.runDuplicateCheck('CHECK-INCOMING').resolved, true);
});
