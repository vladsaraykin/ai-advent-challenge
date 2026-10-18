import { execFileSync, spawn } from 'node:child_process';
import { randomUUID, randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';

// No production URLs accepted. Every invocation owns a new ephemeral container and four fresh DBs.
const root = fileURLToPath(new URL('../', import.meta.url));
const name = `ai-advent-postgres-tests-${randomUUID()}`;
const image = 'pgvector/pgvector:0.8.6-pg16-trixie'; // Same PostgreSQL/pgvector version as deploy/rag-gpu.
const password = randomBytes(24).toString('hex');
let container, child, stopping = false;
const docker = args => execFileSync('docker', args, { encoding: 'utf8', timeout: 60000,
  stdio: ['ignore', 'pipe', 'pipe'] }).trim();
function cleanup() {
  if (stopping || !container) return;
  stopping = true;
  try { docker(['stop', '--time', '3', container]); console.log('Disposable PostgreSQL container stopped and removed.'); }
  catch { console.error(`Could not stop test container ${name}; inspect Docker manually.`) }
}
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
  child?.kill(signal); cleanup(); process.exit(signal === 'SIGINT' ? 130 : 143);
});
try {
  docker(['image', 'inspect', image]); // Fail clearly instead of an implicit/unbounded download.
  container = docker(['run', '--detach', '--rm', '--name', name,
    '--label', 'ai-advent.test-suite=postgres', '--memory', '768m', '--cpus', '2',
    '--tmpfs', '/var/lib/postgresql/data:rw', '--publish', '127.0.0.1::5432',
    '--env', `POSTGRES_PASSWORD=${password}`, image]);
  console.log(`Testing with ${image}; isolated Docker container ${name}.`);
  const deadline = Date.now() + 60000;
  let ready = false;
  while (Date.now() < deadline) {
    try { docker(['exec', container, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres']); ready = true; break; }
    catch { await new Promise(resolve => setTimeout(resolve, 500)) }
  }
  if (!ready) throw new Error('Test PostgreSQL did not become ready.');
  const port = docker(['port', container, '5432/tcp']).match(/^127\.0\.0\.1:(\d+)$/)?.[1];
  if (!port) throw new Error('Unexpected test port binding.');
  for (const db of ['rag_test', 'workspace_test', 'permissions_test', 'review_test'])
    docker(['exec', container, 'createdb', '-U', 'postgres', db]);
  docker(['exec', container, 'psql', '-U', 'postgres', '-d', 'rag_test', '-v', 'ON_ERROR_STOP=1',
    '-c', 'CREATE EXTENSION vector;']);
  docker(['exec', container, 'psql', '-U', 'postgres', '-v', 'ON_ERROR_STOP=1',
    '-c', "CREATE ROLE review_test LOGIN PASSWORD 'review_test'; ALTER DATABASE review_test OWNER TO review_test;"]);
  const url = db => `jdbc:postgresql://127.0.0.1:${port}/${db}`;
  const env = { ...process.env,
    RAG_DATABASE_URL: url('rag_test'), RAG_DATABASE_USERNAME: 'postgres', RAG_DATABASE_PASSWORD: password, MCP_ENABLED: 'false',
    RAG_TEST_DATABASE_URL: url('rag_test'), RAG_TEST_DATABASE_USERNAME: 'postgres', RAG_TEST_DATABASE_PASSWORD: password,
    PROJECT_WORKSPACE_TEST_DATABASE_URL: url('workspace_test'), PROJECT_WORKSPACE_TEST_DATABASE_USERNAME: 'postgres', PROJECT_WORKSPACE_TEST_DATABASE_PASSWORD: password,
    MCP_PERMISSION_TEST_DATABASE_URL: url('permissions_test'), MCP_PERMISSION_TEST_DATABASE_USERNAME: 'postgres', MCP_PERMISSION_TEST_DATABASE_PASSWORD: password,
    REVIEW_TEST_DATABASE_URL: url('review_test') };
  // A PostgreSQL test run must never opt in to real paid/provider dependencies inherited from the shell.
  for (const key of Object.keys(env)) if (/^RAG_TEST_(?:OLLAMA|RERANKER)|^OPENAI_|^LOCAL_LLM_/.test(key)) delete env[key];
  const args = process.argv.slice(2);
  if (args.some(arg => !/^-Dtest=[A-Za-z0-9_,*]+$/.test(arg))) throw new Error('Only an optional -Dtest=ClassName,... filter is supported.');
  child = spawn('mvn', ['-B', 'test', ...args], { cwd: root, env, stdio: 'inherit', shell: false });
  const code = await new Promise((resolve, reject) => {
    child.once('error', reject); child.once('exit', (status, signal) => resolve(signal ? 1 : status ?? 1));
  });
  process.exitCode = code;
} catch (error) {
  console.error(error instanceof Error && !error.message.startsWith('Command failed') ? error.message
    : `Docker test setup failed. Check Docker access and pull ${image} first. No production database was used.`);
  process.exitCode = 1;
} finally { cleanup() }
