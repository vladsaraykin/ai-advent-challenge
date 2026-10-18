import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { Workspace } from './workspace.mjs';

function fixture(t) {
  const root = fs.mkdtempSync(join(tmpdir(), 'project-files-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  execFileSync('git', ['init', '-q', root]);
  fs.mkdirSync(join(root, 'src')); fs.mkdirSync(join(root, 'project/docs'), { recursive: true });
  fs.writeFileSync(join(root, 'src/Api.java'), 'class Api { void search() {} }\n');
  fs.writeFileSync(join(root, 'src/client.js'), 'export const search = () => fetch("/api/search");\n');
  fs.writeFileSync(join(root, 'README.md'), '# Demo\nOld documentation.\n');
  return { root, workspace: new Workspace(root) };
}

test('scenario: discover component usages and analyze three related files', t => {
  const { workspace } = fixture(t);
  assert.deepEqual(workspace.list().files, ['README.md', 'src/Api.java', 'src/client.js']);
  const search = workspace.search('search');
  assert.equal(search.matches.length, 2); assert.equal(search.scannedFiles, 3);
  assert.equal(search.truncated, false);
  const read = workspace.readFiles([...search.matches.map(m => m.path), 'README.md']);
  assert.equal(read.files.length, 3);
  assert.ok(read.files.every(f => f.sha256.length === 64 && !f.truncated));
});

test('scenario: preview, create documentation, read back and repeat without rewriting', t => {
  const { root, workspace } = fixture(t);
  const content = '# Search API\nApi.java and client.js implement search.\n';
  const preview = workspace.preview('project/docs/search.md', content, null);
  assert.ok(preview.diff.includes('+++ b/project/docs/search.md'));
  assert.equal(fs.existsSync(join(root, 'project/docs/search.md')), false);
  const saved = workspace.write('project/docs/search.md', content, null);
  assert.equal(saved.saved, true);
  assert.equal(workspace.read('project/docs/search.md').content, content);
  assert.deepEqual(workspace.write('project/docs/search.md', content, saved.sha256), {
    ...workspace.preview('project/docs/search.md', content, saved.sha256), saved: true
  });
  assert.throws(() => workspace.write('project/docs/search.md', 'overwrite', null), /stale_file/);
});

test('missing user.md is a normal read result and can be previewed, created and verified', t => {
  const { root, workspace } = fixture(t);
  const path = 'project/docs/user.md';
  const read = workspace.readFiles(['src/Api.java', path]);
  assert.equal(read.files[0].exists, true);
  assert.equal(read.files[1].exists, false);
  assert.equal(read.files[1].error, 'file_not_found');
  assert.equal(read.files[1].content, null);
  assert.equal(read.files[1].sha256, null);
  assert.equal(fs.existsSync(join(root, path)), false);
  const content = '# User API\nGenerated from the project source.\n';
  const preview = workspace.preview(path, content, read.files[1].sha256);
  assert.ok(preview.diff.includes('+++ b/project/docs/user.md'));
  const saved = workspace.write(path, content, null);
  assert.equal(saved.saved, true);
  assert.equal(workspace.readFiles([path], 0, saved.sha256).files[0].content, content);
  fs.unlinkSync(join(root, path));
  assert.throws(() => workspace.readFiles([path], 0, saved.sha256), /verification_failed/);
});

test('missing read does not authorize overwriting a file created in the meantime or bypass path rules', t => {
  const { root, workspace } = fixture(t);
  const path = 'project/docs/user.md';
  assert.equal(workspace.readFiles([path]).files[0].exists, false);
  fs.writeFileSync(join(root, path), 'User-created document\n');
  assert.throws(() => workspace.preview(path, 'Model replacement', null), /stale_file/);
  assert.throws(() => workspace.write(path, 'Model replacement', null), /stale_file/);
  assert.equal(fs.readFileSync(join(root, path), 'utf8'), 'User-created document\n');
  for (const denied of ['../missing.md', '/tmp/missing.md', 'data/missing.json', 'src/password.md'])
    assert.throws(() => workspace.readFiles([denied]), /path_not_allowed/);
});

test('updates use optimistic hashes and preserve intervening user edits', t => {
  const { root, workspace } = fixture(t);
  const original = workspace.read('README.md');
  workspace.preview('README.md', '# Updated\n', original.sha256);
  fs.writeFileSync(join(root, 'README.md'), 'User edit\n');
  assert.throws(() => workspace.write('README.md', '# Updated\n', original.sha256), /stale_file/);
  assert.equal(workspace.read('README.md').content, 'User edit\n');
  const saved = workspace.write('README.md', '# Updated\n', workspace.read('README.md').sha256);
  assert.equal(saved.saved, true); assert.equal(workspace.read('README.md').content, '# Updated\n');
  assert.equal(workspace.readFiles(['README.md'], 0, saved.sha256).files[0].sha256, saved.sha256);
  assert.throws(() => workspace.readFiles(['README.md'], 0, original.sha256), /verification_failed/);
});

test('rejects traversal, absolute paths, sensitive names, data and arbitrary code writes', t => {
  const { root, workspace } = fixture(t);
  for (const name of ['../README.md', '/etc/passwd', 'src/../../README.md', '.env', '.git/config', 'data/a.json', 'src/password.json', 'src/Api.java/', 'project/docs/header\ninjection.md']) {
    assert.throws(() => workspace.read(name));
  }
  fs.writeFileSync(join(root, 'src/credentials.json'), '{"key":"not-for-model"}');
  assert.equal(workspace.list().files.includes('src/credentials.json'), false);
  assert.throws(() => workspace.write('src/client.js', 'bad', workspace.read('src/client.js').sha256), /write_path/);
  assert.throws(() => workspace.write('project/docs/no-parent/a.md', 'x', null), /unavailable/);
});

test('rejects symbolic links (including ancestors) and hardlinks', t => {
  const { root, workspace } = fixture(t);
  fs.symlinkSync(join(root, 'README.md'), join(root, 'project/docs/link.md'));
  fs.symlinkSync(join(root, 'src'), join(root, 'project/docs/linked'));
  fs.linkSync(join(root, 'README.md'), join(root, 'project/docs/hard.md'));
  assert.throws(() => workspace.read('project/docs/link.md'), /not_allowed/);
  assert.throws(() => workspace.read('project/docs/linked/client.js'), /not_allowed/);
  assert.throws(() => workspace.read('project/docs/hard.md'), /not_allowed/);
  assert.throws(() => workspace.write('project/docs/link.md', 'bad', null));
  assert.throws(() => workspace.write('project/docs/linked/new.md', 'bad', null));
});

test('bounds reads, search output, file sizes and replacement content; supports read continuation', t => {
  const { root, workspace } = fixture(t);
  fs.writeFileSync(join(root, 'src/large.js'), 'search\n'.repeat(2000));
  const first = workspace.readFiles(['src/large.js']).files[0];
  assert.equal(first.truncated, true); assert.equal(first.nextOffset, 6000);
  const next = workspace.readFiles(['src/large.js'], first.nextOffset).files[0];
  assert.equal(next.sha256, first.sha256); assert.equal(next.offset, 6000);
  const result = workspace.search('search'); assert.equal(result.truncated, true); assert.ok(result.matches.length <= 80);
  assert.throws(() => workspace.readFiles(Array(6).fill('README.md')), /invalid_paths/);
  assert.throws(() => workspace.search(''), /invalid_query/);
  assert.throws(() => workspace.preview('README.md', 'x'.repeat(6001), workspace.read('README.md').sha256), /invalid_content/);
  fs.writeFileSync(join(root, 'src/huge.js'), 'x'.repeat(128001));
  assert.throws(() => workspace.read('src/huge.js'), /too_large/);
});

test('diff is valid for existing/new files including missing trailing newline', t => {
  const { root, workspace } = fixture(t);
  const old = workspace.read('README.md');
  const preview = workspace.preview('README.md', '# Changed', old.sha256);
  execFileSync('git', ['-C', root, 'apply', '--check', '-'], { input: preview.diff });
  execFileSync('git', ['-C', root, 'apply', '--check', '-'], { input: workspace.preview('project/docs/new.md', 'new\n', null).diff });
});
