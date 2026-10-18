import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

test('MCP handshake, tools, multi-file analysis, preview and confirmed write contract', { timeout: 60000 }, async t => {
  const root = fs.mkdtempSync(join(tmpdir(), 'project-files-mcp-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  execFileSync('git', ['init', '-q', root]);
  fs.mkdirSync(join(root, 'project/docs'), { recursive: true });
  for (const name of ['README.md', 'project/docs/api.md', 'project/docs/design.md'])
    fs.writeFileSync(join(root, name), `# ${name}\nSearch API\n`);
  const client = new Client({ name: 'test', version: '1.0.0' });
  t.after(() => client.close());
  await client.connect(new StdioClientTransport({ command: process.execPath,
    args: [fileURLToPath(new URL('./server.mjs', import.meta.url)), root], stderr: 'inherit' }));
  assert.equal(client.getServerVersion().name, 'ai-advent-project-files');
  const { tools } = await client.listTools();
  assert.equal(tools.length, 10);
  assert.equal(tools.find(tool => tool.name === 'project_write_document').annotations.readOnlyHint, false);
  async function call(name, args) {
    const result = await client.callTool({ name, arguments: args });
    return { ...JSON.parse(result.content[0].text), isError: result.isError === true };
  }
  const files = await call('project_list_files', {}); assert.equal(files.files.length, 3);
  const search = await call('project_search_text', { query: 'Search API' }); assert.equal(search.matches.length, 3);
  const read = await call('project_read_files', { paths: files.files }); assert.equal(read.files.length, 3);
  const missing = await call('project_read_files', { paths: ['README.md', 'project/docs/user.md'] });
  assert.equal(missing.isError, false);
  assert.equal(missing.files[0].exists, true);
  assert.equal(missing.files[1].exists, false);
  assert.equal(missing.files[1].error, 'file_not_found');
  const userArgs = { path: 'project/docs/user.md', content: '# User API\n', expectedSha256: missing.files[1].sha256 };
  assert.equal((await call('project_preview_document', userArgs)).isError, false);
  const userSaved = await call('project_write_document', userArgs);
  assert.equal(userSaved.saved, true);
  assert.equal((await call('project_read_files', { paths: [userArgs.path], expectedSha256: userSaved.sha256 })).files[0].content, userArgs.content);
  const args = { path: 'project/docs/summary.md', content: '# API summary\nThree related files.\n', expectedSha256: null };
  const preview = await call('project_preview_document', args); assert.equal(preview.changed, true);
  assert.equal(fs.existsSync(join(root, args.path)), false);
  const saved = await call('project_write_document', args); assert.equal(saved.saved, true);
  assert.equal((await call('project_read_files', { paths: [args.path], expectedSha256: saved.sha256 })).files[0].content, args.content);
  assert.equal((await call('project_read_files', { paths: [args.path], expectedSha256: '0'.repeat(64) })).error, 'verification_failed');
  const stale = await call('project_write_document', args);
  assert.equal(stale.isError, true); assert.equal(stale.error, 'stale_file_read_again');
  const denied = await call('project_read_files', { paths: ['/etc/passwd'] });
  assert.equal(denied.isError, true); assert.equal(denied.error, 'path_not_allowed');
});
