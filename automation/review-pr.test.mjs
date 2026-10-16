import test from 'node:test';
import assert from 'node:assert/strict';
import { collectSnapshot, verifyPatch, publishReview, requestId } from './review-pr.mjs';

const repository = 'vladsaraykin/ai-advent-challenge';
const head = 'b'.repeat(40), base = 'a'.repeat(40);
const pr = { head: { sha: head }, base: { sha: base }, title: 'A PR', changed_files: 1, state: 'open' };
const file = { filename: 'src/Example.java', status: 'modified', patch: '@@ -1 +1 @@\n-old\n+new', additions: 1, deletions: 1, changes: 2 };
const blob = { type: 'file', encoding: 'base64', size: 3, content: Buffer.from('new').toString('base64') };
function api(overrides = {}) {
  const calls = [];
  const github = async (path, options) => {
    calls.push({ path, options });
    if (options) return { id: 100 };
    if (path.includes('/compare/')) return { merge_base_commit: { sha: overrides.mergeBase ?? base } };
    if (path.includes('/comments?')) return overrides.comments ?? [];
    if (path.includes('/contents/')) return overrides.blob ?? blob;
    if (path.includes('/files?')) return overrides.files ?? [file];
    return overrides.pr ?? pr;
  };
  return { github, calls };
}
test('retrieves PR diff and file at the exact head SHA without checking out PR code', async () => {
  const { github, calls } = api();
  const snapshot = await collectSnapshot(github, repository, 1, head);
  assert.equal(snapshot.files[0].patch, file.patch);
  assert.equal(snapshot.files[0].content, 'new');
  assert.ok(calls.some(c => c.path.endsWith(`?ref=${head}`)));
  assert.equal(snapshot.baseSha, base);
});
test('reads removed file at base SHA and retains deleted diff', async () => {
  const mergeBase = 'd'.repeat(40);
  const { github, calls } = api({ mergeBase, files: [{ ...file, status: 'removed', patch: '@@ -1 +0,0 @@\n-old', additions: 0, deletions: 1 }] });
  const snapshot = await collectSnapshot(github, repository, 1, head);
  assert.ok(calls.some(c => c.path.endsWith(`?ref=${mergeBase}`)));
  assert.equal(snapshot.baseSha, mergeBase);
  assert.equal(snapshot.targetSha, base);
});
test('rejects missing and truncated text diffs instead of publishing a partial review', async () => {
  assert.throws(() => verifyPatch({ ...file, additions: 2 }), /incomplete patch/);
  await assert.rejects(collectSnapshot(api({ files: [{ ...file, patch: undefined }] }).github, repository, 1, head), /Text diff is missing/);
  await assert.rejects(collectSnapshot(api({ pr: { ...pr, changed_files: 2 } }).github, repository, 1, head), /Incomplete PR file list/);
});
test('rejects oversized PRs and changed head before paid generation', async () => {
  await assert.rejects(collectSnapshot(api({ pr: { ...pr, changed_files: 41 } }).github, repository, 1, head), /1–40 files/);
  await assert.rejects(collectSnapshot(api({ pr: { ...pr, head: { sha: 'c'.repeat(40) } } }).github, repository, 1, head), /PR has changed/);
});
test('detects a race during fetching and refuses stale review publication', async () => {
  let reads = 0;
  const github = async path => {
    if (path.includes('/compare/')) return { merge_base_commit: { sha: base } };
    if (path.includes('/files?')) return [file];
    if (path.includes('/contents/')) return blob;
    return ++reads === 1 ? pr : { ...pr, head: { sha: 'c'.repeat(40) } };
  };
  await assert.rejects(collectSnapshot(github, repository, 1, head), /changed while fetching/);
  const snapshot = { repository, pullNumber: 1, baseSha: base, targetSha: base, headSha: head };
  const result = { ...snapshot, markdown: '<!-- ai-code-review -->\nReview' };
  await assert.rejects(publishReview(api({ pr: { ...pr, state: 'closed' } }).github, snapshot, result), /stale review/);
});
test('updates only the bot-owned review comment and creates one if absent', async () => {
  const snapshot = { repository, pullNumber: 1, baseSha: base, targetSha: base, headSha: head };
  const result = { ...snapshot, markdown: '<!-- ai-code-review -->\nReview' };
  const { github, calls } = api({ comments: [
    { id: 3, user: { login: 'attacker' }, body: result.markdown },
    { id: 4, user: { login: 'github-actions[bot]' }, body: result.markdown },
  ] });
  await publishReview(github, snapshot, result);
  assert.ok(calls.some(c => c.path.endsWith('/issues/comments/4') && c.options.method === 'PATCH'));
  const fresh = api();
  await publishReview(fresh.github, snapshot, result);
  assert.ok(fresh.calls.some(c => c.options?.method === 'POST'));
});
test('uses deterministic request IDs for retries, but a new attempt has its own cost ledger', () => {
  assert.equal(requestId(repository, '123', '1'), requestId(repository, '123', '1'));
  assert.notEqual(requestId(repository, '123', '1'), requestId(repository, '123', '2'));
  assert.match(requestId(repository, '123', '1'), /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-a[a-f0-9]{3}-[a-f0-9]{12}$/);
});
