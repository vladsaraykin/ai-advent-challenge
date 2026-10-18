import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { Worktrees } from './worktrees.mjs';

test('two real STDIO MCP servers share one task worktree, recover rejected stale edits and commit only task files', { timeout: 60000 }, async t => {
  const dir = fs.mkdtempSync(join(tmpdir(), 'project-mcp-protocol-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const root = join(dir, 'app'), remote = join(dir, 'origin.git');
  const git = (...args) => execFileSync('git', args, { stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' }).trim();
  git('init', '-b', 'main', root); git('init', '--bare', remote);
  git('-C', root, 'config', 'user.name', 'MCP protocol test'); git('-C', root, 'config', 'user.email', 'mcp@example.test');
  fs.writeFileSync(join(root, '.gitignore'), 'data/\n'); fs.writeFileSync(join(root, 'README.md'), '# Original\n');
  git('-C', root, 'add', '.'); git('-C', root, 'commit', '-qm', 'fixture');
  git('-C', root, 'remote', 'add', 'origin', remote); git('-C', root, 'push', 'origin', 'main');
  const id = randomUUID(); new Worktrees(root, execFileSync, { allowLocalFetch: true }).create(id);
  async function connect(relative) {
    const client = new Client({ name: 'test', version: '1' }); t.after(() => client.close());
    await client.connect(new StdioClientTransport({ command: process.execPath,
      args: [fileURLToPath(new URL(relative, import.meta.url)), root], stderr: 'inherit' }));
    return client;
  }
  const files = await connect('../project-files/server.mjs'), projectGit = await connect('./server.mjs');
  const definitions = [...(await files.listTools()).tools, ...(await projectGit.listTools()).tools];
  assert.equal(definitions.length, 19); assert.equal(new Set(definitions.map(tool => tool.name)).size, 19);
  async function call(client, name, args = {}) {
    const result = await client.callTool({ name, arguments: { ...args, workspaceId: id } });
    assert.notEqual(result.isError, true, JSON.stringify(result));
    return JSON.parse(result.content[0].text);
  }
  const created = await call(projectGit, 'project_workspace_create'); assert.equal(created.workspaceState.id, id);
  const before = (await call(files, 'project_read_files', { paths: ['README.md'] })).files[0];
  const edits = [{ before: '# Original', after: '# Updated in task' }];
  await call(files, 'project_preview_edits', { path: before.path, edits, expectedSha256: before.sha256 });
  const written = await call(files, 'project_apply_edits', { path: before.path, edits, expectedSha256: before.sha256 });
  const rejected = await call(files, 'project_apply_edits', { path: before.path, edits, expectedSha256: before.sha256 });
  assert.equal(rejected.operationRejected, true); assert.equal(rejected.saved, false);
  assert.equal((await call(files, 'project_read_files', { paths: ['README.md'], expectedSha256: written.sha256 })).files[0].content, '# Updated in task\n');
  await call(files, 'project_create_file', { path: 'project/docs/new/demo.md', content: '# Demo\n' });
  const status = await call(projectGit, 'project_git_status'); assert.match(status.diff, /new\/demo.md/);
  execFileSync('git', ['-C', new Worktrees(root).open(id).root, 'apply', '--reverse', '--check', '-'],
    { input: status.diff, stdio: ['pipe', 'pipe', 'pipe'] });
  const committed = await call(projectGit, 'project_git_commit', { message: 'Task documentation', treeHash: status.treeHash });
  assert.equal(committed.workspaceState.status, '');
  assert.equal(fs.readFileSync(join(root, 'README.md'), 'utf8'), '# Original\n');
  assert.equal(git('-C', root, 'branch', '--show-current'), 'main');
});
