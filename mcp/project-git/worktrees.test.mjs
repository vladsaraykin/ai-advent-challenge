import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { Worktrees } from './worktrees.mjs';
import { runChecks, runCommand } from './checks.mjs';

function fixture(t) {
  const dir = fs.mkdtempSync(join(tmpdir(), 'project-worktrees-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const root = join(dir, 'app'), origin = join(dir, 'remote.git');
  const git = (...args) => execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  git('init', '-b', 'main', root); git('init', '--bare', origin);
  git('-C', root, 'config', 'user.name', 'MCP tests'); git('-C', root, 'config', 'user.email', 'mcp@example.test');
  fs.mkdirSync(join(root, 'src')); fs.mkdirSync(join(root, 'frontend'));
  fs.writeFileSync(join(root, '.gitignore'), 'data/\n');
  fs.writeFileSync(join(root, 'src/Api.java'), 'class Api { String search() { return "before"; } }\n' + '// preserved\n'.repeat(1000));
  fs.writeFileSync(join(root, 'frontend/api.js'), 'export const search = () => fetch("/api/search");\n');
  fs.writeFileSync(join(root, 'README.md'), '# Search API\n');
  git('-C', root, 'add', '.'); git('-C', root, 'commit', '-qm', 'fixture');
  git('-C', root, 'remote', 'add', 'origin', origin); git('-C', root, 'push', 'origin', 'main');
  return { root, origin, git, worktrees: new Worktrees(root, execFileSync, { allowLocalFetch: true }), id: randomUUID() };
}

test('scenario 1: independently discover three files, patch large source and commit without touching main', t => {
  const { root, git, worktrees, id } = fixture(t);
  const original = fs.readFileSync(join(root, 'src/Api.java'), 'utf8');
  const created = worktrees.create(id); assert.equal(created.workspaceState.branch, `ai/task-${id}`);
  const workspace = worktrees.open(id);
  assert.equal(workspace.search('search').matches.length, 2);
  assert.equal(workspace.readFiles(['src/Api.java', 'frontend/api.js', 'README.md']).files.length, 3);
  const first = workspace.readFiles(['src/Api.java']).files[0]; assert.equal(first.truncated, true);
  const edits = [{ before: 'return "before"', after: 'return "after"' }];
  const preview = workspace.edit('src/Api.java', edits, first.sha256);
  assert.match(preview.diff, /return "after"/);
  workspace.edit('src/Api.java', edits, first.sha256, true);
  assert.equal(workspace.read('src/Api.java').content, original.replace('return "before"', 'return "after"'));
  assert.throws(() => workspace.edit('src/Api.java', edits, first.sha256, true), /stale/);
  const state = worktrees.status(id);
  const committed = worktrees.commit(id, 'Update API', state.treeHash);
  assert.notEqual(committed.workspaceState.headSha, created.workspaceState.headSha);
  assert.equal(committed.workspaceState.status, '');
  assert.equal(fs.readFileSync(join(root, 'src/Api.java'), 'utf8'), original);
  assert.equal(git('-C', root, 'branch', '--show-current'), 'main');
  assert.equal(new Worktrees(root).state(id).workspaceState.headSha, committed.workspaceState.headSha);
  const second = randomUUID(); worktrees.create(second);
  assert.equal(worktrees.open(second).read('src/Api.java').content, original);
});

test('scenario 2: create nested documentation, rename/delete with hashes and bind commit to reviewed files', t => {
  const { worktrees, id } = fixture(t); worktrees.create(id);
  const workspace = worktrees.open(id);
  workspace.create('project/docs/new/auth.md', '# Auth\nCreated from three source files.\n', true);
  const read = workspace.read('project/docs/new/auth.md');
  assert.match(worktrees.status(id).diff, /Created from three source files/);
  assert.throws(() => workspace.create(read.path, 'overwrite', true), /stale/);
  workspace.rename(read.path, 'project/docs/auth.md', read.sha256);
  assert.equal(workspace.readFiles([read.path]).files[0].exists, false);
  const reviewed = worktrees.status(id);
  workspace.create('src/extra.js', 'export const extra = true;\n', true);
  assert.throws(() => worktrees.commit(id, 'Stale review', reviewed.treeHash), /stale/);
  const extra = workspace.read('src/extra.js'); workspace.remove(extra.path, extra.sha256);
  const before = worktrees.status(id); const saved = worktrees.commit(id, 'Document auth', before.treeHash);
  assert.equal(saved.workspaceState.status, '');
  assert.equal(worktrees.open(id).read('project/docs/auth.md').content, read.content);
});

test('blocks protected paths, symlink parents, binary content, arbitrary roots and unreviewed remotes', t => {
  const { root, worktrees, id } = fixture(t);
  assert.throws(() => worktrees.open('../other'), /invalid_workspace/);
  worktrees.create(id); const workspace = worktrees.open(id);
  for (const path of ['.git/config', '.env', '../outside.js', 'data/x.json', 'target/x.java'])
    assert.throws(() => workspace.create(path, 'unsafe', true), /path_not_allowed/);
  workspace.create('.github/workflows/test.yml', 'name: test\n', true);
  fs.symlinkSync(root, join(workspace.root, 'link'));
  assert.throws(() => workspace.create('link/new.txt', 'unsafe', true), /path_not_allowed/);
  fs.writeFileSync(join(workspace.root, 'src/binary.bin'), Buffer.from([255, 254, 253]));
  assert.throws(() => workspace.read('src/binary.bin'), /file_not_text/);
  fs.unlinkSync(join(workspace.root, 'src/binary.bin')); fs.unlinkSync(join(workspace.root, 'link'));
  assert.throws(() => worktrees.push(id, '0'.repeat(40)), /head_changed_or_workspace_dirty/);
  worktrees.commit(id, 'Workflow', worktrees.status(id).treeHash);
  assert.throws(() => worktrees.push(id, worktrees.state(id).workspaceState.headSha), /remote_not_allowed/);
});

test('fixed checks strip application secrets, report failure and timeout without returning raw output', async t => {
  const { worktrees, id } = fixture(t); worktrees.create(id);
  const status = worktrees.status(id);
  const previous = process.env.RAG_DATABASE_PASSWORD; process.env.RAG_DATABASE_PASSWORD = 'test-only-sensitive-value';
  t.after(() => { if (previous === undefined) delete process.env.RAG_DATABASE_PASSWORD; else process.env.RAG_DATABASE_PASSWORD = previous });
  let calls = 0;
  const result = await runChecks(worktrees, id, 'frontend-test', status.treeHash, (command, args, options) => {
    calls++; assert.equal(command, 'npm'); assert.deepEqual(args, ['test']);
    assert.equal(options.env.RAG_DATABASE_PASSWORD, undefined);
    assert.equal(options.env.GH_TOKEN, undefined); assert.equal(options.env.OPENAI_API_KEY, 'disabled-for-project-checks');
    assert.notEqual(options.env.HOME, process.env.HOME); assert.equal(options.timeout, 120000);
    return { status: 1, stdout: 'not returned', stderr: 'not returned' };
  });
  assert.equal(calls, 1); assert.equal(result.passed, false); assert.equal(JSON.stringify(result).includes('not returned'), false);
  await assert.rejects(() => runChecks(worktrees, id, 'shell', status.treeHash), /check_not_allowed/);
  await assert.rejects(() => runChecks(worktrees, id, 'frontend-test', '0'.repeat(64)), /stale/);
  const timeout = await runChecks(worktrees, id, 'backend-test', status.treeHash, () => ({ error: { code: 'ETIMEDOUT' }, status: null }));
  assert.equal(timeout.timedOut, true); assert.equal(timeout.passed, false);
});

test('PR is regular, fixed repository/base/head, verifies account and reuses existing PR without creating again', t => {
  const { worktrees, id } = fixture(t); worktrees.create(id);
  const head = worktrees.state(id).workspaceState.headSha;
  worktrees.remote = () => ({ path: worktrees.open(id).root });
  const originalGit = worktrees.git.bind(worktrees);
  worktrees.git = (path, args) => args[0] === 'ls-remote' ? `${head}\trefs/heads/${worktrees.branch(id)}` : originalGit(path, args);
  const calls = []; let exists = false;
  worktrees.gh = (_path, args) => {
    calls.push(args);
    if (args[0] === 'api') return 'vladsaraykin';
    if (args[1] === 'list') return exists ? '[{"url":"https://github.com/vladsaraykin/ai-advent-challenge/pull/123"}]' : '[]';
    if (args[1] === 'view') return JSON.stringify({ headRefOid: head, headRefName: worktrees.branch(id), baseRefName: 'main', isDraft: false });
    exists = true; return 'https://github.com/vladsaraykin/ai-advent-challenge/pull/123';
  };
  assert.equal(worktrees.pullRequest(id, head, 'Demo', 'Two scenarios').created, true);
  assert.equal(worktrees.pullRequest(id, head, 'Demo', 'Two scenarios').created, false);
  const creates = calls.filter(args => args[1] === 'create'); assert.equal(creates.length, 1);
  assert.equal(creates[0].includes('--draft'), false); assert.equal(creates[0][creates[0].indexOf('--base') + 1], 'main');
  const previousGh = worktrees.gh;
  worktrees.gh = (path, args) => args[1] === 'view'
    ? JSON.stringify({ headRefOid: '0'.repeat(40), headRefName: worktrees.branch(id), baseRefName: 'main', isDraft: false })
    : previousGh(path, args);
  assert.throws(() => worktrees.pullRequest(id, head, 'Demo', ''), /pr_head_changed_or_unknown_result/);
  worktrees.gh = () => 'another-account';
  assert.throws(() => worktrees.pullRequest(id, head, 'Demo', ''), /github_account_not_allowed/);
});

test('push sends the approved immutable SHA, never a possibly moved HEAD', t => {
  const { worktrees, id } = fixture(t); worktrees.create(id);
  const head = worktrees.state(id).workspaceState.headSha;
  worktrees.remote = () => ({ path: worktrees.open(id).root });
  const original = worktrees.git.bind(worktrees); let pushed;
  worktrees.git = (path, args) => {
    if (args[0] === 'push') { pushed = args; return '' }
    return original(path, args);
  };
  assert.equal(worktrees.push(id, head).pushedHeadSha, head);
  assert.deepEqual(pushed, ['push', 'origin', `${head}:refs/heads/${worktrees.branch(id)}`]);
});

test('actual fixed-check process is bounded by a timeout and cannot outlive its process group', async () => {
  const result = await runCommand(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {
    cwd: tmpdir(), env: { PATH: process.env.PATH }, timeout: 100
  });
  assert.equal(result.error.code, 'ETIMEDOUT');
  const missing = await runCommand('/not-an-executable-ai-advent-test', [], { cwd: tmpdir(), env: {}, timeout: 1000 });
  assert.equal(missing.error.code, 'ENOENT');
});

test('source operations preserve UTF-8 BOM, CRLF and executable mode during rename', t => {
  const { worktrees, id } = fixture(t); worktrees.create(id);
  const workspace = worktrees.open(id);
  workspace.create('scripts/demo.sh', '\uFEFF#!/bin/sh\r\necho before\r\n', true);
  fs.chmodSync(join(workspace.root, 'scripts/demo.sh'), 0o755);
  const original = workspace.read('scripts/demo.sh');
  assert.equal(original.content.charCodeAt(0), 0xFEFF);
  const saved = workspace.edit(original.path, [{ before: 'echo before', after: 'echo after' }], original.sha256, true);
  workspace.rename(original.path, 'scripts/renamed.sh', saved.sha256);
  const renamed = workspace.read('scripts/renamed.sh');
  assert.equal(renamed.content, '\uFEFF#!/bin/sh\r\necho after\r\n');
  assert.equal(fs.statSync(join(workspace.root, 'scripts/renamed.sh')).mode & 0o777, 0o755);
});
