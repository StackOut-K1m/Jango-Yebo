import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, it } from 'vitest';
import { resolveRegistry } from './registry';

// 배포 빌드에 MOCK이 남으면 시연 서버에 가짜 잔액이 진짜처럼 뜬다.
// 돈을 세는 화면에서 이건 사고다(CLAUDE.md). MOCK 엔드포인트를 추가할 때마다
// 사람이 배포 빌드를 확인해야 하는데 반드시 까먹으므로 테스트로 고정한다.
it('배포 빌드에서는 MOCK이 남지 않는다', () => {
  const states = Object.values(resolveRegistry({ isProduction: true }));
  expect(states).not.toContain('MOCK');
});


// LIVE 인데 부를 함수가 없는 엔드포인트가 쌓이는 일이 실제로 있었다 —
// 백엔드가 20개를 열어 뒀는데 화면은 그걸 모르고 있었다(2026-09-09).
// registry 를 고치는 사람과 클라이언트를 쓰는 사람이 다르므로 테스트로 고정한다.
it('LIVE 엔드포인트에는 부를 수 있는 클라이언트가 있다', () => {
  const dir = dirname(fileURLToPath(import.meta.url));
  const source = readdirSync(dir)
    .filter((f) => f.endsWith('.ts') && f !== 'registry.ts' && !f.endsWith('.test.ts'))
    .map((f) => readFileSync(join(dir, f), 'utf8'))
    .join('\n');

  const missing = Object.entries(resolveRegistry({ isProduction: false }))
    .filter(([, state]) => state === 'LIVE')
    .map(([key]) => key)
    // refresh 는 client.ts 가 401 을 만났을 때 내부에서 돈다. 화면이 부르지 않는다.
    .filter((key) => key !== 'auth.refresh')
    .filter((key) => !source.includes(`'${key}'`));

  expect(missing).toEqual([]);
});
