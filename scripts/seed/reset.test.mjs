import { describe, expect, it } from 'vitest';

import { SeedPipeline, matchesMaskedAccount } from './pipeline.mjs';

/**
 * 시드 되돌리기 (`resetSeed`).
 *
 * <p>여기서 지키려는 것은 **순서와 되돌리기** 둘이다. 순서는 하나만 어긋나도 서버가 막는다 —
 * 연결된 계좌는 삭제가 409 이고, 잔액이 남은 계좌는 해지가 금액반환계좌를 물어 온다.
 * 되돌리기는 더 조용하다: 해지가 거절당한 계좌를 **비운 채로 두면** 그 계좌를 출금계좌로
 * 쓰는 대출의 상환이 실패한다. 실패가 몇 주 뒤 예정 거래에서 나타나므로 여기서 잡아 둔다.
 *
 * <p>네트워크를 타지 않는다. `api`(우리 백엔드)와 `fin`(금융망)을 가짜로 갈아 끼우고
 * **무엇을 어떤 차례로 불렀는지**만 본다.
 */
function stub({ closeFails = {} } = {}) {
  const calls = [];
  const logs = [];
  const run = new SeedPipeline({
    apiBase: 'http://x',
    repo: '.',
    log: (line, opts) => logs.push({ text: line, bad: opts?.bad === true }),
  });
  run.token = '토큰';
  run.userKey = 'userKey';

  run.api = async (method, path, body) => {
    calls.push(`${method} ${path}${body ? ` ${JSON.stringify(body)}` : ''}`);
    if (path === '/businesses') return { items: [{ businessId: 1, name: '카페 한잔' }] };
    if (path === '/businesses/1/accounts') {
      return {
        items: [
          { accountId: 11, alias: '주거래 사업용', accountNoMasked: '999**********001', status: 'CONNECTED' },
          { accountId: 12, alias: '보조 통장', accountNoMasked: '999**********002', status: 'PENDING_VERIFICATION' },
        ],
      };
    }
    if (path.endsWith('/purge')) {
      return { transactions: 314, evidenceLinks: 2, evidenceMarkedDeleted: 0, settlementAllocations: 5, settlementsRefreshed: 1 };
    }
    return {};
  };

  const balances = {
    '9990000000000001': 3_000_000,
    '9990000000000002': 0,
    '9990000000000009': 5_000,
  };

  run.fin = {
    // 정렬 전 순서로 준다 — closeFinAccounts 가 번호 순으로 도는지 함께 본다.
    accounts: async () => [
      { bankCode: '999', accountNo: '9990000000000002' },
      { bankCode: '999', accountNo: '9990000000000009' },
      { bankCode: '999', accountNo: '9990000000000001' },
      { bankCode: '001', accountNo: '0010000000000001' },
    ],
    balance: async (_userKey, no) => balances[no] ?? 0,
    withdraw: async (_userKey, no, amount, summary) => calls.push(`DD_08 ${no} ${amount} ${summary}`),
    deposit: async (_userKey, no, amount, summary) => calls.push(`DD_09 ${no} ${amount} ${summary}`),
    closeAccount: async (_userKey, no) => {
      calls.push(`DD_14 ${no}`);
      const code = closeFails[no];
      if (!code) return {};
      const err = new Error(`deleteDemandDepositAccount 실패 ${code}`);
      err.code = code;
      throw err;
    },
  };

  return { run, calls, logs };
}

describe('resetSeed', () => {
  it('해제 → 삭제 → 비우기 → 해지 차례로 부른다', async () => {
    const { run, calls } = stub();
    const out = await run.resetSeed();

    expect(calls).toEqual([
      'GET /businesses',
      'GET /businesses/1/accounts',
      // 연결된 계좌만 해제한다. 인증 대기 계좌에까지 해제를 시키면
      // 연결된 적 없는 계좌의 연결을 끊으라는 말이 된다.
      'DELETE /accounts/11',
      // confirm 은 계좌번호가 아니라 별칭이다 — 화면에 마스킹밖에 없기 때문이다.
      'POST /accounts/11/purge {"confirm":"주거래 사업용"}',
      'POST /accounts/12/purge {"confirm":"보조 통장"}',
      // 번호 순. 잔액이 있으면 비우고, 0 이면 출금 없이 바로 해지한다.
      'DD_08 9990000000000001 3000000 해지 전 잔액 정리',
      'DD_14 9990000000000001',
      'DD_14 9990000000000002',
      'DD_08 9990000000000009 5000 해지 전 잔액 정리',
      'DD_14 9990000000000009',
    ]);
    expect(out.purged).toHaveLength(2);
    expect(out.closed).toHaveLength(3);
    expect(out.blocked).toEqual([]);
  });

  it('싸피은행이 아닌 계좌는 건드리지 않는다', async () => {
    const { run, calls } = stub();
    await run.resetSeed();
    expect(calls.some((c) => c.includes('0010000000000001'))).toBe(false);
  });

  it('연동된 적 없는 계좌도 해지하되 로그에서 가른다', async () => {
    const { run, logs } = stub();
    await run.resetSeed();

    // 시드가 중간에 죽으면 만들어만 놓고 연결 못 한 계좌가 남는다. 그것이야말로
    // 다음 시드가 조용히 집어 쓰는 쓰레기라 함께 지우되, 무엇이 그랬는지는 보여 준다.
    const orphan = logs.filter(({ text }) => text.includes('연동된 적 없던 계좌'));
    expect(orphan).toHaveLength(1);
    expect(orphan[0].text).toContain('…0009');
  });

  it('해지가 막히면 뺀 돈을 도로 넣고 그 계좌만 건너뛴다', async () => {
    const { run, calls } = stub({ closeFails: { '9990000000000001': 'A1070' } });
    const out = await run.resetSeed();

    expect(calls.filter((c) => c.includes('9990000000000001'))).toEqual([
      'DD_08 9990000000000001 3000000 해지 전 잔액 정리',
      'DD_14 9990000000000001',
      'DD_09 9990000000000001 3000000 해지 취소 반환',
    ]);
    expect(out.blocked).toEqual([{ accountNo: '9990000000000001', why: '대출의 출금계좌다' }]);
    expect(out.closed).toHaveLength(2);
  });

  /**
   * 해지가 막힌 계좌를 시드가 다시 집어 가면, alignBalance 가 곡선이 원하는 금액으로 그
   * 잔액을 옮긴다. 실제로 **대출 출금계좌가 6,442,803원에서 940,900원으로 깎였다** — 그
   * 계좌에 돈이 있어야 그 대출의 상환이 나간다.
   */
  it('해지 못 한 계좌는 다시 쓰지 않는다', async () => {
    const { run } = stub({ closeFails: { '9990000000000001': 'A1070' } });
    await run.resetSeed();
    expect(run.blockedFin).toEqual(['9990000000000001']);

    // 되돌리기 뒤 금융망에 남은 것은 막힌 계좌 하나뿐이다. 그래도 집어 가지 않고 새로 만든다.
    let made = 0;
    run.fin.accounts = async () => [{ bankCode: '999', accountNo: '9990000000000001' }];
    run.fin.ensureProduct = async () => ({ accountTypeUniqueNo: '999-1' });
    run.fin.createAccount = async () => `99988888888888${made++}`;

    const picked = await run.ensureFinAccounts(2);
    expect(picked).not.toContain('9990000000000001');
    expect(picked).toHaveLength(2);
    expect(made).toBe(2);
  });

  it('되돌리기를 안 했으면 기존 계좌를 그대로 재사용한다', async () => {
    const { run } = stub();
    run.fin.accounts = async () => [
      { bankCode: '999', accountNo: '9990000000000002' },
      { bankCode: '999', accountNo: '9990000000000001' },
    ];
    // blockedFin 이 비어 있으므로 예전 그대로다 — 번호 순으로 둘을 그냥 쓴다.
    await expect(run.ensureFinAccounts(2))
      .resolves.toEqual(['9990000000000001', '9990000000000002']);
  });

  it('모르는 실패는 던지되, 돈을 되돌린 뒤에 던진다', async () => {
    const { run, calls } = stub({ closeFails: { '9990000000000001': 'Q1000' } });
    await expect(run.resetSeed()).rejects.toThrow('Q1000');
    expect(calls.at(-1)).toBe('DD_09 9990000000000001 3000000 해지 취소 반환');
  });

  it('지울 것이 없으면 아무것도 안 한다', async () => {
    const { run } = stub();
    run.api = async (_method, path) => (path === '/businesses' ? { items: [] } : {});
    run.fin.accounts = async () => [];
    await expect(run.resetSeed()).resolves.toEqual({ purged: [], closed: [], blocked: [] });
  });

  /**
   * 해지가 막힌 계좌에는 비웠다 되돌린 자국이 **금융망 오늘 거래로 남는다.** 그 계좌를
   * 다시 연동하면 동기화가 그대로 끌어오는데, 300만원 입금이 매출로 읽히면 예측이 통째로
   * 틀어진다 — 「개발용 초기 잔액」이 잔여 입금의 63% 였던 것과 같은 일이다.
   */
  it('해지 적요는 잔액 맞추기 흔적 제외가 찾는 목록에 있다', async () => {
    const { run } = stub();
    const asked = [];
    run.api = async (_method, path) => {
      if (!path.startsWith('/businesses/1/transactions')) return {};
      asked.push(decodeURIComponent(path.match(/[?&]q=([^&]*)/)?.[1] ?? ''));
      return { items: [] };
    };

    await run.excludeBalanceArtifacts(1);
    expect(asked).toContain('해지 전 잔액 정리');
    expect(asked).toContain('해지 취소 반환');
  });
});

/**
 * 되돌리기 뒤에 계좌가 0개로 남았는지.
 *
 * 백엔드는 계좌 0개를 정식 상태로 다루지만(재계산 건너뜀 · 조회 422) **저장된 예측 실행은
 * 지우지 않는다.** 마지막 계좌가 사라지면 그 재계산이 건너뛰어져 덮을 새 실행이 안 생기고,
 * 조회는 완료된 실행을 먼저 찾으므로 **사라진 계좌로 계산된 옛 곡선을 200 으로 내려준다.**
 * 그 상태를 만드는 쪽이 시드라, 만든 쪽이 말해 준다.
 */
describe('warnIfLeftEmpty', () => {
  /** 되돌리기 뒤 계좌 목록만 바꿔 끼운다 — 다시 세웠나 아닌가가 이 함수가 보는 전부다. */
  const relist = (run, accounts) => {
    run.api = async (_method, path) => {
      if (path === '/businesses') return { items: [{ businessId: 1, name: '카페 한잔' }] };
      if (path === '/businesses/1/accounts') return { items: accounts };
      return {};
    };
  };

  it('계좌가 다시 섰으면 조용하다', async () => {
    const { run, logs } = stub();
    await run.resetSeed();
    logs.length = 0;

    relist(run, [{ accountId: 21, alias: '주거래 사업용' }]);
    await expect(run.warnIfLeftEmpty()).resolves.toEqual([]);
    expect(logs).toEqual([]);
  });

  it('계좌가 0개로 남았으면 사업장 이름과 함께 경고한다', async () => {
    const { run, logs } = stub();
    await run.resetSeed();
    logs.length = 0;

    relist(run, []);
    await expect(run.warnIfLeftEmpty()).resolves.toEqual(['카페 한잔']);

    // 경고는 전부 눈에 띄게 나가야 한다 — 300줄 로그 한가운데 묻히면 안 적은 것과 같다.
    expect(logs.every(({ bad }) => bad)).toBe(true);
    expect(logs[0].text).toContain('카페 한잔');
    expect(logs.map(({ text }) => text).join(' ')).toContain('옛 곡선');
  });

  it('되돌리기를 하지 않았으면 아무것도 안 한다', async () => {
    const { run, logs } = stub();
    await expect(run.warnIfLeftEmpty()).resolves.toEqual([]);
    expect(logs).toEqual([]);
  });

  it('우리가 비우지 않은 빈 사업장은 건드리지 않는다', async () => {
    const { run, logs } = stub();
    await run.resetSeed();
    logs.length = 0;

    // 손으로 만들어 두고 계좌를 안 붙인 사업장. 우리가 비운 것이 아니라 할 말이 없다.
    run.api = async (_method, path) => {
      if (path === '/businesses') {
        return { items: [{ businessId: 1, name: '카페 한잔' }, { businessId: 2, name: '남의 가게' }] };
      }
      if (path === '/businesses/1/accounts') return { items: [{ accountId: 21 }] };
      return { items: [] };
    };
    await expect(run.warnIfLeftEmpty()).resolves.toEqual([]);
    expect(logs).toEqual([]);
  });

  it('확인하러 간 길이 막히면 못 했다는 것을 남긴다', async () => {
    const { run, logs } = stub();
    await run.resetSeed();
    logs.length = 0;

    run.api = async () => { throw new Error('http://x 에 닿지 못했다'); };
    // 조용히 넘어가면 「경고가 없었으니 괜찮다」로 읽힌다. null 은 빈 배열과 다른 답이다.
    await expect(run.warnIfLeftEmpty()).resolves.toBeNull();
    expect(logs).toHaveLength(1);
    expect(logs[0]).toMatchObject({ bad: true });
    expect(logs[0].text).toContain('확인하지 못했다');
  });

  it('지우다 중간에 멈춰도 어느 사업장을 건드렸는지 기억한다', async () => {
    const { run } = stub();
    // 첫 계좌를 지운 직후 금융망이 죽는 시나리오.
    run.fin.accounts = async () => { throw new Error('금융망이 죽었다'); };
    await expect(run.resetSeed()).rejects.toThrow('금융망이 죽었다');

    relist(run, []);
    await expect(run.warnIfLeftEmpty()).resolves.toEqual(['카페 한잔']);
  });
});

/**
 * 마스킹 대조. 마스킹은 **사용자가 친 구분자를 보존**하는데(`AccountService#mask`)
 * 금융망이 주는 번호에는 그것이 없다. 길이로 먼저 자르면 늘 안 맞는다고 나온다.
 */
describe('matchesMaskedAccount', () => {
  const NO = '9990000000000001';
  const withSeparators = (s) => `${s.slice(0, 3)}-${s.slice(3, 10)}-${s.slice(10)}`;

  it('구분자가 어느 쪽에 있든 자리끼리 맞춘다', () => {
    expect(matchesMaskedAccount(NO, '999**********001')).toBe(true);
    expect(matchesMaskedAccount(NO, withSeparators('999**********001'))).toBe(true);
    expect(matchesMaskedAccount(withSeparators(NO), '999**********001')).toBe(true);
    expect(matchesMaskedAccount('110123456789', '110-***-***789')).toBe(true);
  });

  it('보이는 자리가 다르거나 자릿수가 어긋나면 아니다', () => {
    expect(matchesMaskedAccount('9990000000000009', '999**********001')).toBe(false);
    expect(matchesMaskedAccount('999000000000001', '999**********001')).toBe(false);
    expect(matchesMaskedAccount(NO, '')).toBe(false);
  });
});
