// 시드 파이프라인 — 우리 백엔드와 금융망을 오가며 실제로 상태를 세우는 부분.
//
// `scripts/seed.mjs`(CLI)와 `tools/seed-console`(화면)이 <b>이 파일 하나를 같이 쓴다.</b>
// 두 벌로 갈라지면 "화면에서는 됐는데 명령어로는 안 된다"가 생기고, 그게 발표 전날 밤에
// 드러난다. 곡선을 만드는 쪽은 plan.mjs 이고 여기는 그 결과를 밀어 넣기만 한다.
//
// 몇 번을 돌려도 같은 상태가 된다 — 사업장·계좌는 이미 있으면 다시 만들지 않고,
// 업로드는 서버의 중복 판정이 흡수한다.
//
// ⚠ 요청 본문을 로그에 남기지 않는다. 토큰과 userKey 가 들어 있다.
// ⚠ 진행 상황은 log 콜백으로만 내보낸다. console 을 직접 부르지 않는다 — 화면에서도 돌아야 한다.

import { setTimeout as sleep } from 'node:timers/promises';

import { FinClient, SSAFY_BANK, readAuthCode, readEnv } from './fin.mjs';

/**
 * 계좌번호가 마스킹된 표기와 맞는지. `*` 는 아무 자리나 받는다.
 *
 * <p>양쪽에서 <b>구분자를 걷어내고</b> 자리끼리 맞춘다. 마스킹은 사용자가 친 구분자를
 * 그대로 보존하는데({@code AccountService#mask}: `110-123-456789` → `110-***-***789`)
 * 금융망이 주는 번호에는 그것이 없어, 길이부터 어긋나 늘 안 맞는다고 나온다.
 */
export function matchesMaskedAccount(accountNo, masked) {
  if (!masked) return false;
  const pattern = [...String(masked)].filter((c) => c === '*' || (c >= '0' && c <= '9')).join('');
  const digits = String(accountNo).replace(/\D/g, '');
  if (!pattern.length || digits.length !== pattern.length) return false;
  return [...pattern].every((char, index) => char === '*' || char === digits[index]);
}

/** 계좌를 해지하려고 잔액을 비울 때 쓰는 적요. 되돌릴 때는 아래 짝이 같이 나간다. */
const CLOSE_DRAIN = '해지 전 잔액 정리';

/** 해지가 막혀 뺀 돈을 도로 넣을 때. <b>{@link BALANCE_ARTIFACTS} 에 함께 있어야 한다.</b> */
const CLOSE_RESTORE = '해지 취소 반환';

/**
 * 금융망 잔액을 맞추려고 만든 거래의 적요. <b>사장님이 한 거래가 아니다.</b>
 * 문구를 바꾸면 여기도 같이 고친다 — `scripts/fin-account.mjs` 와 `alignBalance`.
 *
 * <p>해지 두 줄이 여기 있는 이유는, <b>해지가 막히면 그 계좌가 살아남기 때문이다.</b>
 * 비웠다 되돌린 자국이 금융망에 오늘 거래로 남고, 그 계좌를 다시 연동하면 동기화가
 * 그대로 끌어온다 — 「개발용 초기 잔액」이 매출로 읽혔던 것과 같은 일이다.
 */
const BALANCE_ARTIFACTS = new Set([
  '개발용 초기 잔액', '전기이월', '전기이월 정산', CLOSE_DRAIN, CLOSE_RESTORE,
]);

/**
 * 계좌 해지가 막히는 사정 (워크북 DD_14). 금융망은 코드로만 알려주므로 여기서 말로 옮긴다.
 *
 * <p>셋 다 <b>우리가 풀 수 없는 조건</b>이라 시드를 멈추지 않고 건너뛴다. 그 계좌만
 * 금융망에 남고, 다음 시드의 {@code ensureFinAccounts} 가 그 번호를 다시 집어 쓴다.
 */
const CLOSE_BLOCKED = {
  A1068: '예·적금 계좌의 연결계좌다',
  A1069: '카드 계좌의 연결계좌다',
  A1070: '대출의 출금계좌다',
};

/** 로그에 쓸 계좌번호. 시드 계좌라도 번호를 통째로 흘릴 이유는 없다 — 뒤 4자리면 짚힌다. */
const tail = (accountNo) => `…${String(accountNo).slice(-4)}`;

/** KST 오늘. 시드를 돌리는 장비의 시간대가 서버와 다를 수 있어 직접 옮긴다. */
const koreanToday = () =>
  new Date(Date.now() + 9 * 3_600_000).toISOString().slice(0, 10);

export class SeedPipeline {
  /**
   * @param log 진행 한 줄을 받는다. CLI 는 console.log, 화면은 NDJSON 으로 흘린다.
   *            둘째 인자로 {@code { bad: true }} 가 올 수 있다 — 양쪽이 눈에 띄게 그린다.
   */
  constructor({ apiBase, repo, authText = '잔고예보', log = () => {} }) {
    this.apiBase = String(apiBase).replace(/\/$/, '');
    this.repo = repo;
    this.authText = authText;
    this.log = log;
    this.token = null;
    this.fin = null;
    this.userKey = null;
    /**
     * 되돌리기가 계좌를 걷어낸 사업장 이름. <b>중간에 멈춰도 남아 있어야 해서</b>
     * 지우는 도중에 채운다 — 끝까지 못 가고 던진 경우가 정확히 경고가 필요한 때다.
     */
    this.emptied = [];
    /**
     * 해지가 막힌 금융망 계좌번호. <b>다시 쓰지 않으려고</b> 들고 있는다 —
     * 까닭은 {@link #ensureFinAccounts}.
     */
    this.blockedFin = [];
  }

  /**
   * Envelope 를 벗겨 {@code data} 만 돌려준다.
   * @param expect 이 오류코드가 오면 던지지 않고 null 을 준다 — 이미 있는 것을 다시 만들 때 쓴다.
   */
  async api(method, path, body, expect = null) {
    const headers = { ...(this.token ? { Authorization: `Bearer ${this.token}` } : {}) };
    let payload = body;
    if (body && !(body instanceof FormData)) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }

    let res;
    try {
      res = await fetch(`${this.apiBase}/api/v1${path}`, { method, headers, body: payload });
    } catch (err) {
      throw new Error(`${this.apiBase} 에 닿지 못했다 — 백엔드가 떠 있는지 확인한다 (${err.message})`, { cause: err });
    }

    const text = await res.text();
    let json;
    try {
      json = text ? JSON.parse(text) : {};
    } catch {
      // Envelope 가 아닌 응답이다. 경로가 백엔드가 아니거나 프록시가 끼어든 것이다.
      throw new Error(`${method} ${path} → ${res.status} 응답이 JSON 이 아니다: ${text.slice(0, 120)}`);
    }

    if (!res.ok) {
      const code = json?.error?.code;
      if (expect && code === expect) return null;
      throw new Error(`${method} ${path} → ${res.status} ${code ?? ''} ${json?.error?.message ?? text.slice(0, 200)}`);
    }
    return json.data;
  }

  /**
   * 금융망 클라이언트를 연다. <b>backend/.env 하나면 된다</b> — userKey 는 이메일로 뽑으므로
   * 공유 시크릿 파일(.env.seed.local)이 없어도 팀원 누구나 돌릴 수 있다.
   */
  async openFin(email) {
    this.fin = new FinClient(readEnv(this.repo, '.env'));
    this.userKey = await this.fin.memberKey(email);
    return this.userKey;
  }

  async login(owner) {
    await this.api('POST', '/auth/signup', owner, 'AUTH_EMAIL_DUPLICATED');
    const session = await this.api('POST', '/auth/login', {
      email: owner.email,
      password: owner.password,
    });
    this.token = session.accessToken;
    this.log(`userId ${session.user.userId}`);
    return session;
  }

  /** 비밀번호 대신 이미 받아 둔 토큰으로 붙는다. 직접 업로드 경로에서 쓴다. */
  useToken(token) {
    this.token = token;
  }

  async ensureBusiness(business) {
    const list = await this.api('GET', '/businesses');
    const found = (list?.items ?? list ?? []).find((b) => b.name === business.name);
    if (found) {
      this.log(`${business.name} 이미 있음`);
      return found.businessId;
    }

    const created = await this.api('POST', '/businesses', {
      name: business.name,
      businessRegNo: business.businessRegNo,
      industryType: business.industryType,
      region: business.region,
      taxationType: business.taxationType,
      openedAt: business.openedOn,
      annualRevenue: business.annualRevenue,
      employeeCount: business.employeeCount,
    });
    this.log(`${business.name} 신규 · businessId ${created.businessId}`);
    return created.businessId;
  }

  /**
   * 금융망에 싸피은행 계좌가 {@code want} 개 있도록 한다. 모자란 만큼만 만든다.
   *
   * <p>번호 순으로 정렬해 돌려준다 — 재실행에서 같은 계좌가 같은 역할을 맡아야
   * '주거래 사업용' 이 매번 다른 계좌가 되는 일이 없다.
   */
  async ensureFinAccounts(want) {
    // 해지가 막힌 계좌는 재사용 대상에서 뺀다. 그 계좌가 살아남은 이유가 <b>예·적금·카드·대출이
    // 그것을 물고 있어서</b>인데, 시드가 집어 가면 alignBalance 가 곡선이 원하는 금액으로
    // 잔액을 옮긴다 — 대출 출금계좌를 6,442,803원에서 940,900원으로 깎는 일이 실제로 났다.
    // 그 계좌에 돈이 있어야 그 대출의 상환이 나간다. 모자란 만큼 새로 만드는 편이 싸다.
    const mine = (await this.fin.accounts(this.userKey))
      .filter((a) => a.bankCode === SSAFY_BANK)
      .map((a) => a.accountNo)
      .filter((accountNo) => !this.blockedFin.includes(accountNo))
      .sort();

    if (mine.length >= want) {
      this.log(`싸피은행 계좌 ${want}개 재사용`);
      return mine.slice(0, want);
    }

    const product = await this.fin.ensureProduct();
    const made = want - mine.length;
    for (let i = 0; i < made; i += 1) {
      mine.push(await this.fin.createAccount(this.userKey, product.accountTypeUniqueNo));
    }
    this.log(`싸피은행 계좌 ${made}개 신규 · ${want - made}개 재사용`);
    return mine.sort();
  }

  /**
   * 계좌를 원하는 상태로 만든다. 이미 그 상태면 건드리지 않는다 — 재실행이 1원을 또 쏘면 안 된다.
   *
   * <p>상태마다 되살리는 문이 다르다. <b>만료된 계좌를 다시 {@code link} 하면 409 다.</b>
   * 1원이 이미 나가 있는 계좌는 {@code /verification}(재발송)이 맡고 재발송 상한도 그쪽에만
   * 있다({@code Account.isRelinkable} Javadoc). {@code link} 로 되살릴 수 있는 것은
   * {@code DISCONNECTED} 와 — 저장된 상태가 실제로 그렇게 바뀐 — {@code VERIFICATION_EXPIRED} 뿐이다.
   *
   * <p>⚠ 목록이 주는 {@code VERIFICATION_EXPIRED} 는 <b>조회 시점 판정</b>이라 DB 에는
   * {@code PENDING_VERIFICATION} 으로 남아 있다(만료 배치를 두지 않으려는 설계). 그래서
   * "목록이 만료라고 하니 다시 link 하자"가 통하지 않는다. 재발송이 답이다.
   */
  async ensureAccount(businessId, spec, accountNo) {
    const existing = (await this.api('GET', `/businesses/${businessId}/accounts`))?.items ?? [];
    const already = existing.find((a) => a.alias === spec.alias);

    if (already && already.status !== 'DISCONNECTED') {
      if (already.status === 'VERIFICATION_EXPIRED') {
        // Redis 발급 기록(TTL 10분)이 지나면 여기로 온다. 재발송하면 PENDING 으로 돌아온다.
        await this.api('POST', `/accounts/${already.accountId}/verification`);
        this.log(`${spec.alias} 인증 만료 → 재발송`);
        return spec.verify ? this.verify(already.accountId, spec, accountNo) : already.accountId;
      }
      this.log(`${spec.alias} 이미 있음 (${already.status})`);
      return already.accountId;
    }

    const linked = await this.api('POST', `/businesses/${businessId}/accounts`, {
      bankCode: SSAFY_BANK,
      accountNo,
      alias: spec.alias,
      isPrimary: spec.primary,
    });
    if (!spec.verify) {
      this.log(`${spec.alias} 등록만 — 인증 대기로 남긴다`);
      return linked.accountId;
    }
    return this.verify(linked.accountId, spec, accountNo);
  }

  /** 통장 적요에서 코드를 읽어 인증을 끝낸다. */
  async verify(accountId, spec, accountNo) {
    const code = await readAuthCode(this.fin, this.userKey, accountNo, this.authText);
    if (!code) throw new Error(`${spec.alias}: 통장 적요에서 인증코드를 못 찾았다`);
    await this.api('POST', `/accounts/${accountId}/verify`, { authCode: code });
    this.log(`${spec.alias} 인증 완료`);
    return accountId;
  }

  async importCsv(accountId, csv) {
    const form = new FormData();
    form.append('file', new Blob([csv], { type: 'text/csv' }), '거래내역.csv');
    const job = await this.api('POST', `/accounts/${accountId}/transactions/import`, form);
    const result = job.resultRef ?? {};
    this.log(
      `신규 ${result.importedCount ?? '?'}건 · 중복 ${result.duplicateCount ?? '?'}건`
      + ` · 거부 ${(result.rejectedRows ?? []).length}건`,
    );
    return result;
  }

  /**
   * 금융망 실잔액을 곡선의 기준일 잔액에 맞춘다.
   *
   * <p>계좌 잔액({@code ACCOUNT.current_balance})은 DD_07 에서 오지 CSV 에서 오지 않는다.
   * 맞추지 않으면 거래는 6개월치인데 잔액만 엉뚱한 값이 나온다.
   *
   * <p>맞추는 거래 한 건은 동기화 때 '전기이월' 로 들어온다. 지우지 않는 이유는, 그것이
   * <b>금융망에서 실시간으로 가져온 오늘 거래</b>라는 증거이기도 해서다.
   */
  async alignBalance(accountNo, target) {
    const current = await this.fin.balance(this.userKey, accountNo);
    const diff = target - current;
    if (diff > 0) await this.fin.deposit(this.userKey, accountNo, diff, '전기이월');
    else if (diff < 0) await this.fin.withdraw(this.userKey, accountNo, -diff, '전기이월 정산');

    const sign = diff >= 0 ? '+' : '−';
    this.log(
      `${current.toLocaleString()} → ${target.toLocaleString()}`
      + ` (${diff === 0 ? '변동 없음' : sign + Math.abs(diff).toLocaleString()})`,
    );
    return { current, diff };
  }

  /**
   * <b>휴무일을 백엔드에 알린다.</b>
   *
   * <p>공휴일은 달력에서 알 수 있지만 <b>가게가 언제 닫는지는 아무도 모른다</b> — 명절에
   * 쉬는 집도 있고 여는 집도 있다. 이력으로 배울 수도 없다: 탐지 창 180일에 휴업이 한 번도
   * 없는 것이 흔하다(이 시드가 그렇다 — 설날은 창보다 앞, 추석은 뒤).
   *
   * <p>그래서 <b>아는 쪽이 등록한다.</b> 실제 서비스에서는 사장님이 화면에서 넣고, 여기서는
   * 생성기가 자기 {@code closedDays} 를 넣는다. 등록하면 정산 투영이 그만큼 줄어든다 —
   * 추석 주 배달 정산 실측 497,400원 대 290,200원.
   */
  async registerClosedDays(businessId, dates) {
    if (!dates?.length) {
      this.log('휴무일 없음');
      return 0;
    }
    const result = await this.api('PUT', `/businesses/${businessId}/calendar-days`, {
      days: dates.map((d) => ({ date: d, dayType: 'CLOSED', name: '휴업' })),
    });
    this.log(`${result?.saved ?? dates.length}일 등록 · 패턴 ${result?.refreshedPatterns ?? 0}개 재생성`);
    return result?.saved ?? dates.length;
  }

  /**
   * <b>상품별 매출을 기간마다 등록한다</b> (API 14.4, P1-22).
   *
   * <p>시연에서 등록 화면(SC-09-3)을 거치지 않기로 해서 시드가 과거 달을 채운다. 출처는
   * {@code MANUAL} 이다 — 스크립트가 넣은 것이 사실이고, 까닭은 `plan.mjs` 의
   * {@code PRODUCT_SALES_SOURCE_TYPE} 에 적어 뒀다.
   *
   * <p>첫 기간만 응답이 {@code bootstrap: true} 다 — 그때는 전 품목이 신규라 서버가 일괄
   * 수락하고, 둘째 달부터 진짜 신규만 잡힌다. 유사 표기 경고가 붙어도 멈추지 않는다.
   *
   * <p><b>같은 기간 재등록은 그 기간을 통째로 교체한다.</b> 몇 번을 돌려도 같은 상태가 된다.
   */
  async registerProductSales(businessId, periods) {
    if (!periods?.length) {
      this.log('등록할 기간이 없다 — 창에 통째로 들어온 달이 없거나 상품 비중이 전부 0 이다');
      return 0;
    }

    let saved = 0;
    for (const period of periods) {
      const result = await this.api('POST', `/businesses/${businessId}/product-sales`, period);
      saved += result?.savedCount ?? period.items.length;
      // 시드가 합계 행을 항목 합으로 만들므로 체크섬은 늘 MATCHED 다. 어긋났다면 생성기가
      // 깨진 것이라, 조용히 넘기지 않고 한 줄 남긴다.
      const mismatch = result?.checksumStatus === 'MISMATCHED' ? ' ⚠ 합계 행이 어긋났다' : '';
      this.log(`${period.periodStart}~${period.periodEnd} ${result?.savedCount ?? 0}행${mismatch}`);
    }
    this.log(`${periods.length}개 기간 · ${saved}행`);
    return saved;
  }

  /**
   * <b>예정 거래 자동 반영을 켠다</b> (API 9.6).
   *
   * <p>켜면 서버가 예정일마다 이 계좌에서 <b>실제로</b> 출금·입금한다. 시연 데이터가 날짜를
   * 넘겨도 스스로 살아 움직이게 하려고 둔 것이고, 대상은 우리가 만든 싸피은행 계좌다.
   *
   * <p>⚠ 같은 이메일로 로컬과 서버를 둘 다 시드하면 <b>두 서버가 같은 금융망 계좌를 본다.</b>
   * 중복 출금은 금융망 거래내역 사전 조회가 막지만, 굳이 양쪽에서 켤 이유는 없다.
   */
  async enableAutoExecution(accountId) {
    await this.api('PATCH', `/accounts/${accountId}/planned-execution`, { enabled: true });
    this.log('자동 반영 켬 — 예정일마다 이 계좌에서 실제로 입출금한다');
    return true;
  }

  /**
   * <b>반복 후보를 탐지해 전부 승인한다.</b>
   *
   * <p>예정 거래는 <b>승인해야 생긴다</b> — 승인 전에는 자동 반영할 것이 하나도 없다.
   * 실제 서비스에서는 사장님이 화면에서 「맞아요」를 누르는 자리이고, 발표에서도 그 흐름을
   * 보이는 편이 낫다. 그래서 이 단계는 <b>선택</b>이고 리허설·로컬에서 쓴다.
   *
   * <p><b>휴일 보정 방향은 실어 보낸다.</b> 탐지가 이력에서 못 읽는 값이 있어서다 —
   * 사정은 `plan.mjs` 의 {@link holidayRules} 주석에 있다. 승인 요청의 필드가 전부
   * 선택이므로(API 9.3) 별도 요청 없이 이 호출에 얹힌다.
   *
   * @param rules `plan.mjs` 의 `holidayRules(tuning)`. 없으면 탐지가 정한 값을 그대로 쓴다
   */
  async detectAndConfirmPatterns(businessId, rules = {}) {
    const detected = await this.api('POST', `/businesses/${businessId}/recurring-patterns/detect`);
    const list = await this.api('GET', `/businesses/${businessId}/recurring-patterns?status=CANDIDATE`);
    const candidates = list?.items ?? [];

    const corrected = [];
    for (const candidate of candidates) {
      // 아는 값만 보낸다. 본문을 비우면 탐지가 정한 금액·주기·공휴일 규칙을 그대로 확정한다.
      const holidayRule = rules[candidate.name];
      const body = holidayRule && holidayRule !== candidate.holidayRule ? { holidayRule } : undefined;
      if (body) corrected.push(`${candidate.name} → ${holidayRule}`);
      await this.api('POST', `/recurring-patterns/${candidate.patternId ?? candidate.id}/confirm`, body);
    }
    this.log(`후보 ${detected?.createdCount ?? candidates.length}건 탐지 · ${candidates.length}건 승인`);
    if (corrected.length) this.log(`  휴일 보정 방향 ${corrected.length}건 지정 — ${corrected.join(' · ')}`);
    return candidates.length;
  }

  async sync(accountId) {
    const job = await this.api('POST', `/accounts/${accountId}/sync`);
    this.log(`jobId ${job.jobId} · ${job.status}`);
    return job;
  }

  /** 작업이 끝날 때까지 기다린다. 못 끝나면 그대로 돌려준다 — 시드는 멈추지 않는다. */
  async awaitJob(jobId, tries = 20, waitMs = 500) {
    for (let i = 0; i < tries; i += 1) {
      const job = await this.api('GET', `/jobs/${jobId}`);
      if (job.status === 'COMPLETED' || job.status === 'FAILED') return job;
      await sleep(waitMs);
    }
    return this.api('GET', `/jobs/${jobId}`);
  }

  /**
   * <b>잔액 맞추기 흔적을 예측에서 뺀다.</b>
   *
   * <p>금융망 샌드박스는 잔액을 직접 정할 수 없어 <b>입금 거래로 만든다</b> — 계좌 개설의
   * `개발용 초기 잔액`(`scripts/fin-account.mjs`)과 {@link #alignBalance} 의 `전기이월` 이다.
   * 실제 사장님 통장에는 없는 거래인데, 동기화되면 매출처럼 보인다.
   *
   * <p>실측(2026-09-13): 사업장 1 의 <b>잔여 입금 63%</b>가 「개발용 초기 잔액」 300만원
   * 한 건이었다. 모델이 그걸 평소 수입으로 읽는다.
   *
   * <p><b>지우지 않고 {@code is_excluded} 로 표시한다.</b> {@link #alignBalance} 주석이
   * 적어 둔 대로 그 거래는 「금융망에서 실시간으로 가져왔다」는 증거이기도 하다. 제외는
   * 원장에 남긴 채 예측 입력에서만 빼는 것이라 그 뜻과 부딪히지 않는다.
   *
   * <p>백엔드가 적요를 보고 거르게 하지 않는 이유는, 그것이 <b>개발 도구의 흔적</b>이지
   * 제품의 규칙이 아니기 때문이다. 운영 코드에 개발용 문자열을 박으면 거기서부터 썩는다.
   */
  async excludeBalanceArtifacts(businessId) {
    // 적요로 찾는다. 예전에는 목록 첫 장을 통째로 받아 걸렀는데, size 는 서버가 100 으로
    // 자르고(application.yml max-page-size) 기본 조회 창은 31일이라 — 거래가 많은 사업장에서
    // 흔적이 조용히 둘째 장으로 밀려났다. 찾을 것이 정해져 있으니 서버에 물어보는 편이 낫다.
    //
    // 창도 넓힌다. 「개발용 초기 잔액」은 계좌를 만든 날 생기므로 이번 시드보다 오래됐을 수 있다.
    const to = koreanToday();
    const from = new Date(Date.parse(`${to}T00:00:00Z`) - 365 * 86_400_000)
      .toISOString().slice(0, 10);

    const found = new Map();
    for (const summary of BALANCE_ARTIFACTS) {
      const query = `from=${from}&to=${to}&size=100&q=${encodeURIComponent(summary)}`;
      const page = await this.api('GET', `/businesses/${businessId}/transactions?${query}`);
      const items = page?.items ?? page?.content ?? [];
      // q 는 부분 일치다 — 「전기이월」로 물으면 「전기이월 정산」도 딸려 온다. 정확히 같은
      // 적요만 고른다. 사장님이 적어 올린 「전기이월 환급금」 같은 것을 집어가지 않기 위해서다.
      for (const t of items) {
        if (BALANCE_ARTIFACTS.has(t.description) && !t.isExcluded) {
          found.set(t.transactionId ?? t.id, t);
        }
      }
    }

    const targets = [...found.values()];
    for (const t of targets) {
      await this.api('PATCH', `/transactions/${t.transactionId ?? t.id}`, { isExcluded: true });
    }
    this.log(targets.length
      ? `${targets.length}건 제외 — ${targets.map((t) => t.description).join(' · ')}`
      : '제외할 것 없음');
    return targets.length;
  }

  async me() {
    return this.api('GET', '/users/me');
  }

  /** 사업장마다 연결 계좌를 붙여 돌려준다. 화면에서 업로드 대상을 고르는 데 쓴다. */
  async businessesWithAccounts() {
    const response = await this.api('GET', '/businesses');
    const businesses = response?.items ?? response ?? [];
    const out = [];
    for (const business of businesses) {
      const accounts = await this.api('GET', `/businesses/${business.businessId}/accounts`);
      out.push({ ...business, accounts: accounts?.items ?? accounts ?? [] });
    }
    return out;
  }

  // ── 되돌리기 ──────────────────────────────────────────────────────────────
  //
  // 위의 것들을 <b>없던 일로 만든다.</b> 잘못 세운 시드를 손으로 걷어내는 일이 번거로워서
  // 둔 문이고, 이어서 위 단계를 그대로 돌리면 처음부터 다시 세워진다.

  /**
   * <b>이 계정에 연동된 계좌를 거래째 지운다</b> (API 7.2 {@code purge}).
   *
   * <p>사업장은 남긴다. 계좌를 다시 세울 때 {@link #ensureBusiness} 가 같은 상호를 찾아
   * 재사용하고, 사업장을 지우면 시드가 만들지 않은 것까지 — 휴무일·상품별 매출 등록분이
   * 거기 매달려 있다 — 함께 날아간다. <b>거래가 딸려 오는 것은 계좌지 사업장이 아니다.</b>
   *
   * <p>지운 결과는 사업장 단위 파생 데이터 중 계좌에 매달린 것까지 걷어낸다 — 예정 거래·
   * 반복 패턴·정산 배분이 그렇다. 증빙과 대출은 <b>연결만 끊긴다</b>(계좌 삭제 설계).
   */
  async purgeLinkedAccounts() {
    const purged = [];

    for (const business of await this.businessesWithAccounts()) {
      for (const account of business.accounts) {
        // CONNECTED 는 서버가 409 로 막는다. 해제를 앞 단계로 두는 것이 설계라 여기서 거친다 —
        // 해제가 auto_execute_planned 를 함께 꺼서 자동 반영 워커와의 경합이 같이 닫힌다.
        if (account.status === 'CONNECTED') {
          await this.api('DELETE', `/accounts/${account.accountId}`);
        }
        // confirm 은 계좌번호가 아니라 <b>별칭</b>이다 — 마스킹이 앞뒤 3자리를 남겨
        // 「뒤 몇 자리」가 화면에서 읽을 수 없는 값이 되기 때문이다(AccountPurgeService).
        const result = await this.api('POST', `/accounts/${account.accountId}/purge`, {
          confirm: account.alias,
        });
        purged.push({ ...account, businessName: business.name, result });
        if (!this.emptied.includes(business.name)) this.emptied.push(business.name);
        this.log(`${account.alias} 삭제 — 거래 ${result?.transactions ?? 0}건`
          + ` · 증빙 연결 ${result?.evidenceLinks ?? 0}건`
          + ` · 정산 배분 ${result?.settlementAllocations ?? 0}건`);
      }
    }

    this.log(purged.length ? `연동 계좌 ${purged.length}개 삭제` : '지울 연동 계좌가 없다');
    return purged;
  }

  /**
   * <b>싸피은행 계좌를 비우고 해지한다</b> (DD_07 → DD_08 → DD_14).
   *
   * <p>우리 DB 에서 지우는 것만으로는 부족하다. 금융망 계좌는 그대로 남아 있고 다음 시드의
   * {@link #ensureFinAccounts} 가 <b>번호 순으로 그 계좌를 다시 집는다</b> — 지난 시드가
   * 남긴 오늘 거래가 동기화로 되살아난다. 그래서 계좌 자체를 없앤다.
   *
   * <p><b>싸피은행(999) 것만 건드린다.</b> 개발·시연 계좌는 거기 만든다는 것이 팀 관례라
   * ({@code scripts/fin-account.mjs}) 이 userKey 의 999 계좌는 전부 도구가 만든 것이다.
   * 연동된 적 없는 계좌도 지운다 — 시드가 중간에 죽으면 만들어만 놓고 연결 못 한 계좌가
   * 남는데, 그것이야말로 다음 시드가 조용히 집어 쓰는 쓰레기다.
   *
   * <p>해지에는 잔액이 0 이어야 한다 — 아니면 금액반환계좌를 물어 오는데 돌려줄 곳이 없다.
   * 그래서 DD_08 로 비우고 부른다. <b>해지가 막히면 뺀 돈을 도로 넣는다</b>: 대출 출금계좌를
   * 빈 채로 두면 그 대출의 상환이 조용히 실패한다(A1070).
   *
   * @param masks 연동돼 있던 계좌의 마스킹 표기. 로그에서 남은 계좌를 가르는 데만 쓴다
   */
  async closeFinAccounts(masks = []) {
    const mine = (await this.fin.accounts(this.userKey))
      .filter((a) => a.bankCode === SSAFY_BANK)
      .map((a) => a.accountNo)
      .sort();

    if (!mine.length) {
      this.log('해지할 싸피은행 계좌가 없다');
      return { closed: [], blocked: [] };
    }

    const closed = [];
    const blocked = [];

    for (const accountNo of mine) {
      const linked = masks.some((mask) => matchesMaskedAccount(accountNo, mask));
      const balance = await this.fin.balance(this.userKey, accountNo);
      if (balance > 0) await this.fin.withdraw(this.userKey, accountNo, balance, CLOSE_DRAIN);

      try {
        await this.fin.closeAccount(this.userKey, accountNo);
      } catch (err) {
        // 우리가 비운 계좌가 살아남았다. 금융망을 우리가 보기 전 상태로 돌려놓고 넘어간다.
        if (balance > 0) {
          await this.fin.deposit(this.userKey, accountNo, balance, CLOSE_RESTORE);
        }
        const why = CLOSE_BLOCKED[err.code];
        if (!why) throw err;
        blocked.push({ accountNo, why });
        this.log(`${tail(accountNo)} 해지 못 함 — ${why}`);
        continue;
      }

      closed.push(accountNo);
      this.log(`${tail(accountNo)} 해지`
        + `${balance > 0 ? ` — ${balance.toLocaleString()}원 출금 후` : ' — 잔액 0'}`
        + `${linked ? '' : ' · 연동된 적 없던 계좌'}`);
    }

    // 남은 계좌는 시드가 다시 집어 가면 안 된다 — ensureFinAccounts 가 이 목록을 본다.
    this.blockedFin = blocked.map((b) => b.accountNo);

    this.log(`${closed.length}개 해지${blocked.length ? ` · ${blocked.length}개 남음 — 다시 쓰지 않는다` : ''}`);
    return { closed, blocked };
  }

  /**
   * <b>시드를 처음부터 다시 세울 수 있는 상태로 되돌린다.</b> 되돌릴 수 없다.
   *
   * <p>순서가 한쪽으로만 맞다. 우리 DB 를 먼저 지운다 — 금융망을 먼저 해지하면, 우리 쪽
   * 삭제가 실패했을 때 <b>없는 계좌를 가리키는 계좌 행</b>이 남는다. 동기화도 잔액 조회도
   * 안 되는 계좌가 화면에 멀쩡히 떠 있는 상태가 가장 고치기 어렵다. 반대로 금융망 쪽이
   * 실패하면 계좌만 하나 떠도는 것이고, 그건 로그에 적어 두면 사람이 처리할 수 있다.
   */
  async resetSeed() {
    const purged = await this.purgeLinkedAccounts();
    const masks = purged.map((a) => a.accountNoMasked).filter(Boolean);
    const { closed, blocked } = await this.closeFinAccounts(masks);
    return { purged, closed, blocked };
  }

  /**
   * <b>비운 사업장에 계좌가 다시 섰는지 본다.</b> 안 섰으면 크게 경고한다.
   *
   * <h2>계좌 0개가 위험한 자리는 예보 화면 하나다</h2>
   *
   * <p>백엔드는 계좌 0개를 정식 상태로 다룬다 — 재계산은 건너뛰고({@code ForecastRunWorker}),
   * 04:00 배치는 대상에서 빼고, 조회는 {@code 422 FORECAST_INSUFFICIENT_DATA} 다. 거기까지는
   * 괜찮다.
   *
   * <p><b>새는 곳은 저장된 실행이다.</b> 계좌 삭제는 {@code FORECAST_RUN} 을 지우지 않는데,
   * 그 근거가 「재계산이 새 실행으로 덮는다」이다. <b>마지막 계좌를 지우면 그 재계산이
   * 건너뛰어져 덮을 것이 안 생긴다.</b> 조회는 완료된 실행을 먼저 찾으므로 422 가 아니라
   * <b>사라진 계좌로 계산된 옛 곡선을 그대로 200 으로 내려준다.</b>
   *
   * <p>시드가 끝까지 돌면 새 실행이 덮어 수 초짜리 창이다. 문제는 <b>중간에 멈췄을 때</b>다 —
   * 1원 인증은 통장 적요에서 코드를 읽는 단계라 실제로 잘 깨진다. 그때 화면에는 없는 계좌의
   * 잔액으로 그린 곡선이 떠 있고, <b>돈을 세는 화면이라 조용히 틀리는 쪽이다.</b>
   *
   * <p>고치는 자리는 백엔드지만 여기서 막는 이유는, 그 상태를 <b>만드는 쪽이 시드</b>이고
   * 만든 쪽이 말해 주는 것이 가장 확실해서다. 해제만 한 사업장까지 답이 바뀌는 백엔드
   * 변경은 팀이 따로 정할 일이다.
   *
   * @return 계좌가 0개로 남은 사업장 이름. 확인하지 못했으면 {@code null}
   */
  async warnIfLeftEmpty() {
    if (!this.emptied.length) return [];

    let empty;
    try {
      const wanted = new Set(this.emptied);
      empty = (await this.businessesWithAccounts())
        .filter((b) => wanted.has(b.name) && !b.accounts.length)
        .map((b) => b.name);
    } catch (err) {
      // 확인하러 간 길이 막혔다. 못 했다는 사실 자체를 남긴다 — 조용히 넘어가면
      // 「경고가 없었으니 괜찮다」로 읽힌다.
      this.log(`⚠ 계좌가 다시 섰는지 확인하지 못했다 — ${err.message}`, { bad: true });
      return null;
    }

    if (!empty.length) return [];

    this.log(`⚠ 계좌가 0개로 남았다 — ${empty.join(' · ')}`, { bad: true });
    this.log('  예보 화면은 빈 상태가 아니라 «사라진 계좌로 계산된 옛 곡선»을 그대로 보여준다.', { bad: true });
    this.log('  계좌 삭제가 예측 실행을 지우지 않는데 계좌가 없으면 재계산이 건너뛰어져, 덮을', { bad: true });
    this.log('  새 실행이 안 생기기 때문이다. 시드를 끝까지 다시 돌려 계좌를 세우면 사라진다.', { bad: true });
    return empty;
  }
}
