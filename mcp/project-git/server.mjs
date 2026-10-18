import { resolve } from 'node:path';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import { Worktrees } from './worktrees.mjs';
import { runChecks, stopActiveChecks } from './checks.mjs';
import { Workspace } from '../project-files/workspace.mjs';
import { rejectedBeforeMutation } from './results.mjs';

const worktrees = new Worktrees(resolve(process.argv[2] || '.'));
const server = new McpServer({ name: 'ai-advent-project-git', version: '2.0.0' });
process.once('SIGTERM', () => { stopActiveChecks(); process.exit(143) });
process.once('SIGINT', () => { stopActiveChecks(); process.exit(130) });
process.stdin.once('end', stopActiveChecks);
function tool(name, description, schema, action, readOnly = true) {
  server.registerTool(name, { description, inputSchema: { ...schema, workspaceId: z.string().uuid().optional(),
    workspaceRevision: z.number().int().nonnegative().optional() },
    annotations: { readOnlyHint: readOnly, destructiveHint: !readOnly, openWorldHint: !readOnly } }, async args => {
    try { return { content: [{ type: 'text', text: JSON.stringify(await action(args)) }] }; }
    catch (error) {
      if (args.workspaceId && rejectedBeforeMutation(error)) return { content: [{ type: 'text', text: JSON.stringify({
        error: error.message, operationRejected: true, saved: false,
        notice: 'Operation rejected before mutation. Read/status again and correct parameters. Do not claim success.' }) }] };
      return { isError: true, content: [{ type: 'text', text: JSON.stringify({
      error: /^[a-z_]+$/.test(error.message) ? error.message : 'git_operation_failed',
      notice: 'No automatic retry of writes. Inspect task status/remote/PR before deciding how to recover.' }) }] }; }
  });
}
const required = a => { if (!a.workspaceId) throw new Error('workspace_required'); return a.workspaceId; };
const head = { expectedHeadSha: z.string().regex(/^[0-9a-f]{40}$/) };
tool('get_current_branch', 'Read current branch of the bound task workspace (or configured project for normal read-only chat).', {},
  a => a.workspaceId ? worktrees.state(a.workspaceId) : { branch: worktrees.git(worktrees.root, ['branch', '--show-current']) });
tool('list_project_files', 'List safe project files in the bound task checkout.', {},
  a => a.workspaceId ? worktrees.open(a.workspaceId).list() : new Workspace(worktrees.root).list());
tool('project_workspace_create', 'Create or inspect this chat task worktree and ai/task-UUID branch from fetched origin/main. Never switch the running checkout. Call first in /files.', {},
  a => worktrees.create(required(a)), false);
tool('project_git_status', 'Read task branch, base/head commit, working status and bounded unified diff; treeHash binds commit to reviewed content. Untracked paths include hashes.', {},
  a => worktrees.status(required(a)));
tool('project_git_remote_status', 'Read remote task branch SHA and existing open PR to reconcile uncertain push/PR. Does not push, create or merge.', {},
  a => worktrees.remoteStatus(required(a)));
tool('project_git_commit', 'Commit only this task checkout, binding the operation to treeHash from status. No hooks, no amend/reset or edits to main.',
  { message: z.string().min(1).max(200), treeHash: z.string().regex(/^[0-9a-f]{64}$/) },
  a => worktrees.commit(required(a), a.message, a.treeHash), false);
tool('project_git_push', 'Push EXACT expectedHeadSha to this task branch in origin vladsaraykin/ai-advent-challenge. ALWAYS separate one-shot user approval. No force or push to main.', head,
  a => worktrees.push(required(a), a.expectedHeadSha), false);
tool('project_git_create_pr', 'Create a NON-DRAFT GitHub PR from the already pushed task branch to main using gh as vladsaraykin. ALWAYS separate one-shot approval. Reuse an existing open PR; never merge.',
  { ...head, title: z.string().min(1).max(200), body: z.string().max(6000) },
  a => worktrees.pullRequest(required(a), a.expectedHeadSha, a.title, a.body), false);
tool('project_run_checks', 'Run one fixed check in the task checkout: backend tests, frontend tests or frontend build. No shell/custom args. Restricted env, isolated HOME, 120s timeout. Not an OS sandbox. Results bound to treeHash; no raw logs/secrets.',
  { check: z.enum(['backend-test', 'frontend-test', 'frontend-build']), treeHash: z.string().regex(/^[0-9a-f]{64}$/) },
  a => runChecks(worktrees, required(a), a.check, a.treeHash), false);
await server.connect(new StdioServerTransport());
