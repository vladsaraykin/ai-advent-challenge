import * as fs from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawn } from 'node:child_process';

const children = new Set();
function kill(child) {
  try { if (process.platform !== 'win32') process.kill(-child.pid, 'SIGKILL'); else child.kill('SIGKILL'); }
  catch { /* Already exited. */ }
}
export function stopActiveChecks() { for (const child of children) kill(child) }

export function runCommand(command, args, options) {
  return new Promise(resolve => {
    let timedOut = false;
    const child = spawn(command, args, { cwd: options.cwd, env: options.env, detached: process.platform !== 'win32',
      shell: false, stdio: ['ignore', 'ignore', 'ignore'] });
    children.add(child);
    const stop = () => { kill(child); children.delete(child) };
    const timer = setTimeout(() => { timedOut = true; stop() }, options.timeout);
    child.once('error', error => { clearTimeout(timer); stop(); resolve({ error, status: null }) });
    child.once('close', status => { clearTimeout(timer); stop(); resolve({ status, error: timedOut ? { code: 'ETIMEDOUT' } : undefined }) });
  });
}

export async function runChecks(worktrees, id, check, treeHash, execute = runCommand) {
  const status = worktrees.status(id);
  if (status.treeHash !== treeHash) throw new Error('stale_workspace_review_diff_again');
  const workspace = worktrees.open(id);
  const commands = {
    'backend-test': ['mvn', ['-B', '-Dstyle.color=never', 'test'], workspace.root],
    'frontend-test': ['npm', ['test'], join(workspace.root, 'frontend')],
    'frontend-build': ['npm', ['run', 'build'], join(workspace.root, 'frontend')]
  };
  if (!Object.hasOwn(commands, check)) throw new Error('check_not_allowed');
  const home = fs.mkdtempSync(join(tmpdir(), 'ai-project-check-'));
  const env = { PATH: process.env.PATH, HOME: home, TMPDIR: home, CI: 'true',
    GH_CONFIG_DIR: join(home, 'gh'), GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: '/dev/null',
    GIT_TERMINAL_PROMPT: '0', MAVEN_OPTS: `-Duser.home=${home}`, npm_config_userconfig: join(home, 'npmrc'),
    RAG_ENABLED: 'false', RAG_DATABASE_URL: 'jdbc:postgresql://127.0.0.1:1/disabled',
    OPENAI_API_KEY: 'disabled-for-project-checks', MCP_ENABLED: 'false' };
  if (process.env.JAVA_HOME) env.JAVA_HOME = process.env.JAVA_HOME;
  const [command, args, cwd] = commands[check];
  const started = Date.now();
  try {
    const result = await execute(command, args, { cwd, env, encoding: 'utf8', timeout: 120000,
      killSignal: 'SIGKILL', maxBuffer: 2_000_000, stdio: ['ignore', 'pipe', 'pipe'] });
    let after;
    try { after = worktrees.status(id); }
    catch { throw new Error('check_result_uncertain'); }
    return { ...after, check, treeHash, currentTreeHash: after.treeHash,
      passed: !result.error && result.status === 0 && after.treeHash === treeHash,
      exitCode: result.status, durationMs: Date.now() - started, timedOut: result.error?.code === 'ETIMEDOUT',
      failureReason: result.error?.code === 'ENOENT' ? 'command_not_available' : null,
      notice: 'No raw subprocess output returned. Missing dependencies must be prepared by operator. backend-test uses mvn test without external PostgreSQL opt-ins; Docker DB integration is separate. Restricted environment is not OS isolation.' };
  } finally { fs.rmSync(home, { recursive: true, force: true }); }
}
