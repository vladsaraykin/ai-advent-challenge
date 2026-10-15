import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';

const repository = resolve(process.argv[2] || '.');
const MAX_OUTPUT_CHARS = 24_000;
const hiddenName = /(^|\/)(\.env($|\.)|.*\.(pem|key|p12|pfx)|.*(secret|credential|password).*)/i;

function git(args) {
  return execFileSync('git', ['-C', repository, ...args], {
    encoding: 'utf8',
    timeout: 5_000,
    maxBuffer: 1_000_000,
    stdio: ['ignore', 'pipe', 'pipe']
  }).trim();
}

const server = new McpServer({ name: 'ai-advent-project-git', version: '1.0.0' });

server.registerTool('get_current_branch', {
  title: 'Получить текущую git-ветку',
  description: 'Возвращает имя текущей ветки закреплённого checkout проекта. Только чтение.',
  annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false }
}, async () => {
  const branch = git(['branch', '--show-current']);
  return { content: [{ type: 'text', text: branch || '(detached HEAD)' }] };
});

server.registerTool('list_project_files', {
  title: 'Список файлов проекта',
  description: 'Возвращает имена отслеживаемых Git файлов проекта; содержимое не читается.',
  annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false }
}, async () => {
  const files = git(['ls-files', '--cached']).split('\n').filter(Boolean).filter(path => !hiddenName.test(path));
  const visible = files.slice(0, 500);
  const suffix = files.length > visible.length ? `\n…ещё ${files.length - visible.length} файлов не показано` : '';
  return { content: [{ type: 'text', text: `${visible.join('\n')}${suffix}`.slice(0, MAX_OUTPUT_CHARS) }] };
});

await server.connect(new StdioServerTransport());
