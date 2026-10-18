import * as fs from 'node:fs';
import { resolve, join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { Workspace } from '../project-files/workspace.mjs';

export const REPOSITORY = 'vladsaraykin/ai-advent-challenge';
export const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
const sha = value => typeof value === 'string' && /^[0-9a-f]{40}$/.test(value);

/** Worktree isolation is NOT OS isolation. The MCP host is trusted; no arbitrary shell tool. */
export class Worktrees {
  constructor(root, run = execFileSync, { allowLocalFetch = false } = {}) {
    this.root = fs.realpathSync(root); this.run = run;
    this.allowLocalFetch = allowLocalFetch;
    this.directory = join(this.root, 'data', 'project-workspaces');
  }
  environment() {
    const result = { GIT_TERMINAL_PROMPT: '0', GH_PROMPT_DISABLED: '1' };
    for (const key of ['PATH','HOME','TMPDIR','LANG','LC_ALL','SSH_AUTH_SOCK','GH_CONFIG_DIR','GH_TOKEN','GITHUB_TOKEN',
      'HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','NO_PROXY','http_proxy','https_proxy','all_proxy','no_proxy'])
      if (process.env[key]) result[key] = process.env[key];
    return result;
  }
  git(root, args, timeout = 15000) {
    const output = this.run('git', ['-c', 'core.hooksPath=/dev/null', '-c', 'core.fsmonitor=false', '-C', root, ...args],
      { encoding: 'utf8', timeout, maxBuffer: 2_000_000, stdio: ['ignore', 'pipe', 'pipe'],
        env: this.environment() });
    // Patch bytes (including trailing whitespace/newlines) must remain applicable.
    return args[0] === 'diff' ? output : output.trimEnd();
  }
  location(id) {
    if (!uuid(id)) throw new Error('invalid_workspace');
    return join(this.directory, id);
  }
  branch(id) { this.location(id); return `ai/task-${id}`; }
  open(id) {
    const path = this.location(id);
    if (!fs.existsSync(path) || fs.lstatSync(path).isSymbolicLink() || fs.realpathSync(path) !== path)
      throw new Error('workspace_not_created');
    if (this.git(path, ['rev-parse', '--show-toplevel']) !== path
        || this.git(path, ['branch', '--show-current']) !== this.branch(id)
        || this.git(path, ['rev-parse', '--git-common-dir']) !== this.git(this.root, ['rev-parse', '--absolute-git-dir']))
      throw new Error('workspace_identity_mismatch');
    return new Workspace(path, { sourceEditing: true });
  }
  create(id) {
    const path = this.location(id);
    if (fs.existsSync(path)) return this.state(id);
    const origin = this.git(this.root, ['remote', 'get-url', 'origin']);
    if (!this.allowLocalFetch && !this.allowedRemote(origin)) throw new Error('remote_not_allowed');
    this.git(this.root, ['fetch', '--no-tags', 'origin', 'refs/heads/main:refs/remotes/origin/main'], 60000);
    const baseSha = this.git(this.root, ['rev-parse', 'refs/remotes/origin/main']);
    if (!sha(baseSha)) throw new Error('invalid_base');
    // Refuse a pre-existing branch: never reset or silently reuse another checkout.
    const data = join(this.root, 'data');
    if (fs.existsSync(data) && (fs.lstatSync(data).isSymbolicLink() || !fs.lstatSync(data).isDirectory()))
      throw new Error('unsafe_workspace_directory');
    fs.mkdirSync(this.directory, { recursive: true, mode: 0o700 });
    if (fs.realpathSync(this.directory) !== this.directory) throw new Error('unsafe_workspace_directory');
    this.git(this.root, ['worktree', 'add', '-b', this.branch(id), path, baseSha]);
    this.git(path, ['config', `branch.${this.branch(id)}.aiBase`, baseSha]);
    return this.state(id);
  }
  state(id) {
    const workspace = this.open(id), path = workspace.root;
    const baseSha = this.git(path, ['config', '--get', `branch.${this.branch(id)}.aiBase`]);
    const headSha = this.git(path, ['rev-parse', 'HEAD']);
    return { workspaceState: { id, branch: this.branch(id), baseSha, headSha,
      relativePath: `data/project-workspaces/${id}`, status: this.git(path, ['status', '--porcelain=v1', '--untracked-files=all']).slice(0, 18000) } };
  }
  status(id) {
    const state = this.state(id), path = this.open(id).root;
    const workspace = this.open(id);
    const changed = this.git(path, ['diff', '--name-only', '-z', state.workspaceState.baseSha]).split('\0').filter(Boolean);
    if (changed.length > 200) throw new Error('workspace_too_large');
    for (const name of changed) {
      if (fs.existsSync(join(path, name))) workspace.read(name);
      else workspace.path(name, true, true);
    }
    const diff = this.git(path, ['diff', '--no-ext-diff', '--no-textconv', state.workspaceState.baseSha, '--', '.']);
    const status = this.git(path, ['status', '--porcelain=v1', '--untracked-files=all']);
    // Includes untracked file content so an approval cannot commit a changed new file.
    const names = this.git(path, ['ls-files', '--others', '--exclude-standard', '-z']).split('\0').filter(Boolean).sort();
    if (names.length > 100) throw new Error('workspace_too_large');
    const files = names.map(name => this.open(id).read(name)); // fail closed on secrets/binary/unsafe untracked files
    const treeHash = createHash('sha256').update(JSON.stringify({ head: state.workspaceState.headSha, diff, status,
      files: files.map(file => [file.path, file.sha256]) })).digest('hex');
    const fullDiff = diff + files.map(file => {
      if (!file.content) return `diff --git a/${file.path} b/${file.path}\nnew file mode 100644\n`;
      const lines = file.content.replace(/\n$/, '').split('\n');
      return `--- /dev/null\n+++ b/${file.path}\n@@ -0,0 +1,${lines.length} @@\n`
        + lines.map(line => '+' + line).join('\n') + '\n' + (file.content.endsWith('\n') ? '' : '\\ No newline at end of file\n');
    }).join('');
    return { ...state, treeHash, diff: fullDiff.slice(0, 40000), truncated: fullDiff.length > 40000,
      untracked: files.map(({ path, sha256 }) => ({ path, sha256 })) };
  }
  commit(id, message, treeHash) {
    const before = this.status(id), path = this.open(id).root;
    if (before.treeHash !== treeHash) throw new Error('stale_workspace_review_diff_again');
    if (!message || message.length > 200 || /[\x00-\x1f]/.test(message)) throw new Error('invalid_commit_message');
    const changed = this.git(path, ['diff', '--name-only', '-z', 'HEAD']).split('\0').filter(Boolean);
    const removed = new Set(this.git(path, ['ls-files', '--deleted', '-z']).split('\0').filter(Boolean));
    for (const name of changed) { if (!removed.has(name)) this.open(id).read(name); else this.open(id).path(name, true, true); }
    if (!before.workspaceState.status) throw new Error('nothing_to_commit');
    // This checkout belongs only to this task. Never stage the running application's checkout.
    this.git(path, ['add', '--all', '--', '.']);
    this.git(path, ['-c', 'commit.gpgsign=false', 'commit', '-m', message], 30000);
    return this.state(id);
  }
  remote(id, expectedHeadSha) {
    const state = this.state(id), path = this.open(id).root;
    if (!sha(expectedHeadSha) || state.workspaceState.headSha !== expectedHeadSha || state.workspaceState.status)
      throw new Error('head_changed_or_workspace_dirty');
    if (!this.allowedRemote(this.git(path, ['remote', 'get-url', 'origin']))
        || !this.allowedRemote(this.git(path, ['remote', 'get-url', '--push', 'origin']))) throw new Error('remote_not_allowed');
    return { state, path };
  }
  allowedRemote(value) {
    return [`git@github.com:${REPOSITORY}.git`, `https://github.com/${REPOSITORY}.git`, `https://github.com/${REPOSITORY}`].includes(value);
  }
  push(id, expectedHeadSha) {
    const { path } = this.remote(id, expectedHeadSha);
    this.git(path, ['push', 'origin', `${expectedHeadSha}:refs/heads/${this.branch(id)}`], 60000);
    return { ...this.state(id), pushed: true, pushedHeadSha: expectedHeadSha };
  }
  remoteStatus(id) {
    const state = this.state(id);
    const { path } = this.remote(id, state.workspaceState.headSha);
    const remoteHeadSha = this.git(path, ['ls-remote', 'origin', `refs/heads/${this.branch(id)}`], 30000).split(/\s/)[0] || null;
    if (this.gh(path, ['api', 'user', '--jq', '.login']) !== 'vladsaraykin') throw new Error('github_account_not_allowed');
    const prs = JSON.parse(this.gh(path, ['pr', 'list', '--repo', REPOSITORY, '--head', this.branch(id), '--base', 'main', '--state', 'open', '--json', 'url']));
    const prUrl = prs[0]?.url;
    if (prUrl && !/^https:\/\/github\.com\/vladsaraykin\/ai-advent-challenge\/pull\/\d+$/.test(prUrl)) throw new Error('invalid_pr_result');
    return { ...state, remoteHeadSha, ...(prUrl ? { prUrl } : {}) };
  }
  gh(path, args) {
    return this.run('gh', args, { cwd: path, encoding: 'utf8', timeout: 60000,
      maxBuffer: 200000, stdio: ['ignore', 'pipe', 'pipe'], env: this.environment() }).trim();
  }
  pullRequest(id, expectedHeadSha, title, body) {
    const { path } = this.remote(id, expectedHeadSha);
    if (!title?.trim() || title.length > 200 || typeof body !== 'string' || body.length > 6000) throw new Error('invalid_pr');
    const remoteHead = this.git(path, ['ls-remote', 'origin', `refs/heads/${this.branch(id)}`], 30000).split(/\s/)[0];
    if (remoteHead !== expectedHeadSha) throw new Error('push_exact_commit_first');
    const account = this.gh(path, ['api', 'user', '--jq', '.login']);
    if (account !== 'vladsaraykin') throw new Error('github_account_not_allowed');
    const existing = JSON.parse(this.gh(path, ['pr', 'list', '--repo', REPOSITORY, '--head', this.branch(id),
      '--base', 'main', '--state', 'open', '--json', 'url']));
    const url = existing[0]?.url || this.gh(path, ['pr', 'create', '--repo', REPOSITORY, '--head', this.branch(id),
      '--base', 'main', '--title', title, '--body', body]);
    if (!/^https:\/\/github\.com\/vladsaraykin\/ai-advent-challenge\/pull\/\d+$/.test(url)) throw new Error('invalid_pr_result');
    const observed = JSON.parse(this.gh(path, ['pr', 'view', url, '--repo', REPOSITORY,
      '--json', 'headRefOid,headRefName,baseRefName,isDraft']));
    if (observed.headRefOid !== expectedHeadSha || observed.headRefName !== this.branch(id)
        || observed.baseRefName !== 'main' || observed.isDraft !== false) throw new Error('pr_head_changed_or_unknown_result');
    return { ...this.state(id), prUrl: url, created: !existing.length };
  }
}
