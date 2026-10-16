import { readFile, appendFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';

const MARKER = '<!-- ai-code-review -->';
const MAX_CHARACTERS = 120000;
const MAX_FILES = 40;

export function requestId(repository, runId, attempt) {
  const hex = createHash('sha256').update(`${repository}:${runId}:${attempt}`).digest('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-a${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}

export function verifyPatch(file) {
  if (!file.patch) return;
  let additions = 0, deletions = 0;
  for (const line of file.patch.split('\n')) {
    if (line.startsWith('+')) additions++;
    if (line.startsWith('-')) deletions++;
  }
  if (additions !== file.additions || deletions !== file.deletions) {
    throw new Error('GitHub returned an incomplete patch; split this PR into smaller changes.');
  }
}

export async function collectSnapshot(github, repository, number, expectedHead) {
  const prefix = `/repos/${repository}`;
  const pr = await github(`${prefix}/pulls/${number}`);
  if (pr.head.sha !== expectedHead || pr.state !== 'open') throw new Error('PR has changed or closed; a newer workflow must review it.');
  if (pr.changed_files < 1 || pr.changed_files > MAX_FILES) throw new Error(`Review supports 1–${MAX_FILES} files; split this PR.`);
  // PR diffs use the merge base, not necessarily the current tip of the target branch.
  const comparison = await github(`${prefix}/compare/${pr.base.sha}...${pr.head.sha}`);
  const baseSha = comparison.merge_base_commit?.sha;
  if (!/^[a-f0-9]{40}$/.test(baseSha ?? '')) throw new Error('Cannot determine the PR merge base.');
  const files = await github(`${prefix}/pulls/${number}/files?per_page=100`);
  if (files.length !== pr.changed_files) throw new Error('Incomplete PR file list.');
  const snapshot = [];
  let characters = 0;
  for (const file of files) {
    verifyPatch(file);
    const ref = file.status === 'removed' ? baseSha : pr.head.sha;
    const encodedPath = file.filename.split('/').map(encodeURIComponent).join('/');
    const blob = await github(`${prefix}/contents/${encodedPath}?ref=${ref}`);
    if (blob.type !== 'file' || blob.encoding !== 'base64' || blob.size > MAX_CHARACTERS) {
      throw new Error('A changed file is unsupported or too large; split this PR.');
    }
    const bytes = Buffer.from(blob.content, 'base64');
    const binary = bytes.includes(0);
    let content = '';
    if (!binary) {
      try { content = new TextDecoder('utf-8', { fatal: true }).decode(bytes); }
      catch { throw new Error('Changed text file is not UTF-8.'); }
    }
    if (!file.patch && !binary && file.changes > 0) throw new Error('Text diff is missing; review would be incomplete.');
    const item = { path: file.filename, status: file.status, patch: file.patch ?? '', content };
    characters += item.path.length + item.patch.length + item.content.length;
    if (characters > MAX_CHARACTERS) throw new Error('Diff and files exceed 120000 characters; split this PR.');
    snapshot.push(item);
  }
  const current = await github(`${prefix}/pulls/${number}`);
  if (current.head.sha !== pr.head.sha || current.base.sha !== pr.base.sha) throw new Error('PR changed while fetching files.');
  if (!snapshot.some(f => f.patch.trim())) throw new Error('No textual diff to review.');
  return { repository, pullNumber: number, baseSha, targetSha: pr.base.sha, headSha: pr.head.sha, title: pr.title, files: snapshot };
}

async function fetchJson(url, options, label, timeout = 60000, maxBytes = 4 * 1024 * 1024) {
  const response = await fetch(url, { ...options, redirect: 'error', signal: AbortSignal.timeout(timeout) });
  if (!response.ok) throw new Error(`${label}: HTTP ${response.status}`); // Never print upstream response bodies.
  const reader = response.body.getReader();
  const chunks = [];
  let size = 0;
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > maxBytes) throw new Error(`${label}: response too large`);
      chunks.push(value);
    }
  } finally { await reader.cancel(); }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

export async function publishReview(github, snapshot, result) {
  if (result.headSha !== snapshot.headSha || result.baseSha !== snapshot.baseSha || result.repository !== snapshot.repository
      || result.pullNumber !== snapshot.pullNumber || !result.markdown?.startsWith(MARKER) || result.markdown.length > 60000) {
    throw new Error('Backend returned an invalid review identity or report.');
  }
  const prefix = `/repos/${snapshot.repository}`;
  const current = await github(`${prefix}/pulls/${snapshot.pullNumber}`);
  if (current.state !== 'open' || current.head.sha !== snapshot.headSha || current.base.sha !== snapshot.targetSha) {
    throw new Error('PR changed during review; stale review not published.');
  }
  let previous;
  for (let page = 1; page <= 30; page++) {
    const comments = await github(`${prefix}/issues/${snapshot.pullNumber}/comments?per_page=100&page=${page}`);
    previous = comments.find(comment => comment.user?.login === 'github-actions[bot]' && comment.body?.startsWith(MARKER)) ?? previous;
    if (comments.length < 100) break;
  }
  return previous
    ? github(`${prefix}/issues/comments/${previous.id}`, { method: 'PATCH', body: { body: result.markdown } })
    : github(`${prefix}/issues/${snapshot.pullNumber}/comments`, { method: 'POST', body: { body: result.markdown } });
}

export async function main(env = process.env) {
  for (const key of ['GITHUB_TOKEN', 'GITHUB_REPOSITORY', 'GITHUB_EVENT_PATH', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT',
    'AI_REVIEW_API_URL', 'AI_REVIEW_USERNAME', 'AI_REVIEW_PASSWORD']) {
    if (!env[key]) throw new Error(`Missing setting: ${key}`);
  }
  const event = JSON.parse(await readFile(env.GITHUB_EVENT_PATH, 'utf8'));
  const repository = env.GITHUB_REPOSITORY;
  if (!event.pull_request || event.pull_request.head.repo.full_name !== repository
      || event.pull_request.user.login !== repository.split('/')[0]) throw new Error('Only owner PRs from this repository are allowed.');
  const api = new URL(env.AI_REVIEW_API_URL);
  if (api.username || api.password || api.search || api.hash || api.pathname !== '/api/reviews'
      || !(api.protocol === 'https:' || (api.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(api.hostname)))) {
    throw new Error('AI_REVIEW_API_URL must be HTTPS /api/reviews, or loopback HTTP with a self-hosted runner.');
  }
  const github = (path, options = {}) => fetchJson(`https://api.github.com${path}`, {
    method: options.method ?? 'GET',
    headers: { Authorization: `Bearer ${env.GITHUB_TOKEN}`, Accept: 'application/vnd.github+json',
      'Content-Type': 'application/json', 'X-GitHub-Api-Version': '2022-11-28' },
    body: options.body ? JSON.stringify(options.body) : undefined,
  }, 'GitHub');
  const snapshot = await collectSnapshot(github, repository, event.number, event.pull_request.head.sha);
  const { targetSha, ...payload } = snapshot;
  const id = requestId(repository, env.GITHUB_RUN_ID, env.GITHUB_RUN_ATTEMPT);
  console.log(`Review PR #${snapshot.pullNumber}, head ${snapshot.headSha}, files ${snapshot.files.length}, request ${id}`);
  const result = await fetchJson(api, {
    method: 'POST', headers: { 'Content-Type': 'application/json',
      Authorization: `Basic ${Buffer.from(`${env.AI_REVIEW_USERNAME}:${env.AI_REVIEW_PASSWORD}`).toString('base64')}` },
    body: JSON.stringify({ requestId: id, ...payload }),
  }, 'Review backend', 660000);
  await publishReview(github, snapshot, result);
  if (env.GITHUB_STEP_SUMMARY) await appendFile(env.GITHUB_STEP_SUMMARY, result.markdown);
  console.log(`Review published, findings ${result.findings.length}.`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
