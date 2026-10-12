import { useEffect, useMemo, useState } from 'react';
import type { ChangeEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import {
  deleteEvidence,
  formatDateTime,
  getEvidence,
  getEvidenceFile,
  listEvidences,
  patchEvidence,
  patchEvidenceExtraction,
  reExtractEvidence,
  uploadEvidence,
} from '@jangoyebo/core';
import type {
  EvidenceQuery,
  EvidenceType,
  Extraction,
  ExtractionItemPatch,
  ExtractionPatch,
  OcrStatus,
} from '@jangoyebo/core';
import { useBusiness } from '@/business/useBusiness';
import { EmptyState } from '@/components/EmptyState';
import { Field, FormError } from './AuthShell';
import { Icon } from './icons';
import { money } from './model';
import { PageShell, Section } from './PageShell';
import { EvidenceLinkSection } from './EvidenceLink';

/**
 * 증빙 (SC-07-1·2·3, P0-10·P1-02, API 13.1~13.5·13.7).
 *
 * 올리면 서버가 AI 추출을 걸고, 읽은 값은 사장님이 확인해야 확정된다 — 자동 승인은 없다.
 * 검토 화면에서 거래에 연결하고(13.4 — EvidenceLink), 종류를 고치고 지우고(13.5), 다시 읽는다(13.7).
 */

const EVIDENCE_TYPE: Record<EvidenceType, string> = {
  RECEIPT: '영수증',
  TAX_INVOICE: '세금계산서',
  INVOICE: '계산서·거래명세서',
  CASH_RECEIPT: '현금영수증',
  SETTLEMENT_STATEMENT: '플랫폼 정산명세서',
  CONTRACT: '임대차계약서',
  PAYROLL: '급여대장',
  OTHER: '종류 미정',
};

const OCR_STATUS: Record<OcrStatus, { label: string; icon: string }> = {
  QUEUED: { label: '읽기 대기', icon: 'clock' },
  PROCESSING: { label: '읽는 중', icon: 'refresh' },
  EXTRACTED: { label: '확인 대기', icon: 'info' },
  CONFIRMED: { label: '확정', icon: 'check' },
  FAILED: { label: '읽지 못함', icon: 'warning' },
  SKIPPED: { label: '읽을 대상 아님', icon: 'close' },
};

/** 워커 검산 사유(API 20.4). 모르는 코드는 코드 그대로 보인다. */
const REVIEW_REASON: Record<string, string> = {
  AMOUNTS_INCONSISTENT: '과세·면세·부가세를 더한 값이 합계와 맞지 않습니다.',
  ITEMS_SUM_INCONSISTENT: '품목 금액을 더한 값이 합계와 맞지 않습니다.',
  VAT_RATIO_INCONSISTENT: '부가세가 과세 금액의 10%와 맞지 않습니다.',
  ITEM_ROWS_MISALIGNED: '품목 이름과 금액 줄이 밀려 읽혔을 수 있습니다.',
  BUSINESS_NO_INVALID: '사업자등록번호의 검증 자리가 맞지 않습니다.',
  DATE_INVALID: '발행일이 날짜로 읽히지 않았습니다.',
  TOTAL_MISSING: '합계 금액을 찾지 못했습니다.',
  DATE_MISSING: '발행일을 찾지 못했습니다.',
  SUPPLIER_MISSING: '공급자(상호)를 찾지 못했습니다.',
  LOW_CONFIDENCE: '확신이 낮게 읽힌 값이 있습니다.',
};

const ACCEPT = ['image/jpeg', 'image/png', 'application/pdf'];
const MAX_BYTES = 10 * 1024 * 1024;
const READING: OcrStatus[] = ['QUEUED', 'PROCESSING'];

// ponytail: 강조 기준은 근거가 약하다 — 워커 실측에서 0.7·0.85 모두 무작위와 비슷했다(13.2).
//   게이트가 아니라 눈길을 줄 곳이라 넓게 0.9 로 잡았다. 수정 이력이 쌓이면 다시 잰다.
const LOW_CONFIDENCE = 0.9;

/**
 * 방금 올린 원본. 이 탭에서 올린 파일은 브라우저가 이미 들고 있어 서버에 다시 묻지 않는다.
 * 없으면(새로고침·다른 기기) 검토 화면이 서버에서 원본을 받는다(GET /evidences/{id}/file).
 */
const previews = new Map<number, { url: string; type: string }>();

function ReviewBadge({ status, needsReview }: { status: OcrStatus; needsReview?: boolean }) {
  // 세 상태다 — 값이 없으면 추출 전이지 검산 통과가 아니다.
  if (needsReview === true)
    return (
      <span className="v7-settlement-status" data-status="MISMATCHED">
        <Icon name="warning" size={14} />
        확인 필요
      </span>
    );
  if (needsReview === false)
    return (
      <span className="v7-settlement-status" data-status="MATCHED">
        <Icon name="check" size={14} />
        검산 통과
      </span>
    );
  return <span className="v7-settlement-status">{READING.includes(status) ? '추출 전' : '—'}</span>;
}

/* ══ 증빙함 + 올리기 ═══════════════════════════════════════════════ */

type Filter = 'all' | 'review' | 'waiting' | 'confirmed';
const FILTERS: { id: Filter; label: string; query: EvidenceQuery }[] = [
  { id: 'all', label: '전체', query: {} },
  { id: 'review', label: '확인 필요', query: { needsReview: true } },
  { id: 'waiting', label: '확인 대기', query: { ocrStatus: 'EXTRACTED' } },
  { id: 'confirmed', label: '확정', query: { ocrStatus: 'CONFIRMED' } },
];

interface Notice {
  tone: 'good' | 'bad';
  title: string;
  lines: string[];
}

export function EvidenceScreen() {
  const { current } = useBusiness();
  const businessId = current?.businessId;
  const queryClient = useQueryClient();
  const [filter, setFilter] = useState<Filter>('all');
  const [files, setFiles] = useState<File[]>([]);
  const [evidenceType, setEvidenceType] = useState<EvidenceType | ''>('');
  const [notice, setNotice] = useState<Notice | null>(null);
  // 올린 뒤 파일 칸을 비우려고 input 을 새로 만든다. 파일 칸은 값을 코드로 지울 수 없다.
  const [inputKey, setInputKey] = useState(0);

  const list = useQuery({
    queryKey: ['v7', 'evidences', businessId, filter],
    enabled: businessId !== undefined,
    // 확인이 필요한 것을 위로 올리고, 같은 무리 안에서는 최근 순이다(13.2).
    queryFn: () =>
      listEvidences(businessId as number, {
        ...(FILTERS.find((f) => f.id === filter)?.query ?? {}),
        sort: ['needsReview,desc', 'createdAt,desc'],
        size: 50,
      }),
    // 읽는 중인 증빙이 있을 때만 다시 묻는다. 결과는 Job 이 아니라 목록의 상태로 본다.
    refetchInterval: (q) => {
      const r = q.state.data;
      return r?.ready && r.ok && r.data.items.some((e) => READING.includes(e.ocrStatus)) ? 3000 : false;
    },
  });

  const upload = useMutation({
    mutationFn: async (picked: File[]) => {
      const failed: string[] = [];
      let done = 0;
      for (const file of picked) {
        const result = await uploadEvidence(
          businessId as number,
          file,
          file.name,
          evidenceType ? { evidenceType } : {},
        );
        if (result.ready && result.ok) {
          done += 1;
          previews.set(result.data.evidenceId, { url: URL.createObjectURL(file), type: file.type });
        } else {
          failed.push(
            `${file.name} — ${result.ready ? result.error.message : '증빙 기능이 아직 준비되지 않았습니다.'}`,
          );
        }
      }
      return { done, failed };
    },
    onSuccess: ({ done, failed }) => {
      setNotice(
        failed.length === 0
          ? {
              tone: 'good',
              title: `${done}건을 올렸습니다`,
              lines: ['AI가 읽는 동안 아래 목록의 상태가 바뀝니다. 다 읽으면 검토에서 값을 확인해 주세요.'],
            }
          : { tone: 'bad', title: `${done}건 올림 · ${failed.length}건 실패`, lines: failed },
      );
      setFiles([]);
      setInputKey((k) => k + 1);
      queryClient.invalidateQueries({ queryKey: ['v7', 'evidences'] });
    },
    onError: () =>
      setNotice({ tone: 'bad', title: '올리지 못했습니다', lines: ['연결이 원활하지 않습니다. 잠시 뒤 다시 시도해 주세요.'] }),
  });

  function pick(e: ChangeEvent<HTMLInputElement>) {
    const picked = [...(e.target.files ?? [])];
    const rejected = picked.filter((f) => !ACCEPT.includes(f.type) || f.size > MAX_BYTES);
    setFiles(picked.filter((f) => !rejected.includes(f)));
    setNotice(
      rejected.length === 0
        ? null
        : {
            tone: 'bad',
            title: `${rejected.length}건은 올릴 수 없습니다`,
            lines: rejected.map(
              (f) => `${f.name} — ${f.size > MAX_BYTES ? '10MB를 넘습니다' : 'JPG·PNG·PDF만 올릴 수 있습니다'}`,
            ),
          },
    );
  }

  const result = list.data;
  // PostgreSQL 은 내림차순에서 값이 없는 행(추출 전)을 맨 앞에 둔다. 서버 정렬만으로는
  // 「확인 필요」가 추출 전 증빙 아래로 밀려서, 받은 목록 안에서 한 번 더 올린다(정렬은 안정적이다).
  // ponytail: 한 번에 받은 50건 안에서만 올린다. 서버가 NULLS LAST 로 정렬하게 되면 지운다.
  const items =
    result?.ready && result.ok
      ? [...result.data.items].sort((a, b) => Number(b.needsReview === true) - Number(a.needsReview === true))
      : [];
  const total = result?.ready && result.ok ? (result.data.totalElements ?? items.length) : 0;
  const listError = result?.ready && !result.ok ? result.error.message : null;

  return (
    <PageShell
      title="증빙"
    >
      {notice && (
        <div
          className={`v7-note ${notice.tone === 'good' ? 'v7-note--good' : 'v7-note--bad'}`}
          role={notice.tone === 'good' ? 'status' : 'alert'}
        >
          <Icon name={notice.tone === 'good' ? 'check' : 'warning'} size={18} />
          <div>
            <strong>{notice.title}</strong>
            {notice.lines.map((line) => (
              <span key={line}>{line}</span>
            ))}
          </div>
        </div>
      )}

      <div className="v7-cols">
        <Section title="증빙 올리기">
          <div className="v7-settlement-form">
            <Field label="파일" hint="JPG·PNG·PDF, 한 장에 10MB까지 · 여러 장을 한 번에 고를 수 있습니다">
              <input key={inputKey} type="file" accept={ACCEPT.join(',')} multiple onChange={pick} />
            </Field>
            <Field label="종류" hint="모르면 비워 두세요. AI가 추정합니다">
              <select value={evidenceType} onChange={(e) => setEvidenceType(e.target.value as EvidenceType | '')}>
                <option value="">자동으로 추정</option>
                {(Object.keys(EVIDENCE_TYPE) as EvidenceType[])
                  .filter((type) => type !== 'OTHER')
                  .map((type) => (
                    <option key={type} value={type}>
                      {EVIDENCE_TYPE[type]}
                    </option>
                  ))}
              </select>
            </Field>
            <button
              type="button"
              className="auth-submit"
              disabled={files.length === 0 || upload.isPending || businessId === undefined}
              onClick={() => upload.mutate(files)}
            >
              {upload.isPending ? '올리는 중…' : files.length > 0 ? `${files.length}건 올리기` : '올리기'}
            </button>
          </div>
        </Section>

        <Section title="확정까지">
          <ul className="v7-rows">
            <li>
              <Icon name="clock" size={16} />
              <span className="d">
                <b>읽기 대기 · 읽는 중</b>
                <small>올리자마자 AI가 읽기 시작합니다. 이 화면을 열어 두면 상태가 저절로 바뀝니다.</small>
              </span>
            </li>
            <li>
              <Icon name="warning" size={16} />
              <span className="d">
                <b>확인 필요</b>
                <small>읽은 숫자끼리 맞지 않는 것을 위로 올립니다. 검산을 통과한 것도 읽기가 틀렸을 수 있어 모두 확인이 필요합니다.</small>
              </span>
            </li>
            <li>
              <Icon name="check" size={16} />
              <span className="d">
                <b>확정</b>
                <small>사장님이 확인한 값만 확정됩니다. 고칠 것이 없어도 확정을 눌러 주세요.</small>
              </span>
            </li>
          </ul>
        </Section>
      </div>

      <Section title={`증빙함 ${total}건`}>
        <div className="v7-chips" role="group" aria-label="증빙 거르기">
          {FILTERS.map((f) => (
            <button key={f.id} type="button" aria-pressed={filter === f.id} onClick={() => setFilter(f.id)}>
              {f.label}
            </button>
          ))}
        </div>

        {list.isPending ? (
          <p className="v7-note">불러오는 중…</p>
        ) : listError ? (
          <div className="v7-note v7-note--bad" role="alert">
            <Icon name="warning" size={18} />
            <div>
              <strong>증빙을 불러오지 못했습니다</strong>
              <span>{listError}</span>
            </div>
          </div>
        ) : items.length === 0 ? (
          <EmptyState
            title={filter === 'all' ? '올린 증빙이 없습니다' : '해당하는 증빙이 없습니다'}
            hint={filter === 'all' ? '영수증을 올리면 여기에 쌓입니다. 신고 직전에 몰아서 모으면 빠지는 것이 생깁니다.' : undefined}
          />
        ) : (
          <div className="tx-table-wrap v7-scroll" tabIndex={0} aria-label="증빙 목록">
            <table className="tx-table">
              <thead>
                <tr>
                  <th scope="col">올린 시각</th>
                  <th scope="col">증빙</th>
                  <th scope="col">읽기</th>
                  <th scope="col">검산</th>
                  <th scope="col">발행일</th>
                  <th scope="col" className="tx-num">
                    합계
                  </th>
                  <th scope="col">
                    <span className="sr-only">검토</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {items.map((e) => {
                  const status = OCR_STATUS[e.ocrStatus];
                  const reviewable = e.ocrStatus === 'EXTRACTED' || e.ocrStatus === 'CONFIRMED';
                  return (
                    <tr key={e.evidenceId}>
                      <td data-label="올린 시각" className="v7-date">{formatDateTime(e.uploadedAt)}</td>
                      <td data-label="증빙">
                        {e.fileName}
                        <small>{EVIDENCE_TYPE[e.evidenceType] ?? e.evidenceType}</small>
                      </td>
                      <td data-label="읽기">
                        <span className="v7-settlement-status" data-status={e.ocrStatus === 'CONFIRMED' ? 'MATCHED' : undefined}>
                          <Icon name={status?.icon ?? 'info'} size={14} />
                          {status?.label ?? e.ocrStatus}
                        </span>
                      </td>
                      <td data-label="검산">
                        <ReviewBadge status={e.ocrStatus} needsReview={e.needsReview} />
                      </td>
                      <td data-label="발행일" className="v7-date">{e.issuedDate ?? '—'}</td>
                      <td data-label="합계" className="tx-num">{e.totalAmount === undefined ? '—' : `${money(e.totalAmount)}원`}</td>
                      <td>
                        {reviewable && (
                          <Link className="v7-secondary-button" to={`/evidences/${e.evidenceId}`}>
                            {e.ocrStatus === 'EXTRACTED' ? '검토' : '보기'}
                          </Link>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Section>
    </PageShell>
  );
}

/* ══ 검토 ═════════════════════════════════════════════════════════ */

const moneyText = z
  .string()
  .refine((v) => v.trim() === '' || /^\d[\d,]*$/.test(v.trim()), '0 이상의 숫자만 입력해 주세요.');
const quantityText = z
  .string()
  .refine((v) => v.trim() === '' || /^\d+(\.\d+)?$/.test(v.trim().replace(/,/g, '')), '숫자만 입력해 주세요.');

const schema = z.object({
  supplierName: z.string(),
  supplierBusinessNo: z
    .string()
    .refine((v) => v.trim() === '' || /^[\d-]+$/.test(v.trim()), '숫자와 - 만 입력해 주세요.'),
  issuedDate: z.string(),
  totalAmount: moneyText,
  vatAmount: moneyText,
  discountAmount: moneyText,
  items: z.array(
    z.object({ rawName: z.string(), quantity: quantityText, unitPrice: moneyText, amount: moneyText }),
  ),
});
type ReviewForm = z.infer<typeof schema>;

const TEXT_FIELDS = [
  { key: 'supplierName', label: '공급자(상호)', type: 'text' },
  { key: 'supplierBusinessNo', label: '사업자등록번호', type: 'text' },
  { key: 'issuedDate', label: '발행일', type: 'date' },
] as const;
const MONEY_FIELDS = [
  { key: 'totalAmount', label: '합계' },
  { key: 'vatAmount', label: '부가세' },
  { key: 'discountAmount', label: '할인' },
] as const;

const text = (v: string | number | undefined) => (v === undefined || v === null ? '' : String(v));
const toNumber = (v: string) => Number(v.trim().replace(/,/g, ''));

function toForm(x: Extraction | undefined): ReviewForm {
  return {
    supplierName: text(x?.supplierName),
    supplierBusinessNo: text(x?.supplierBusinessNo),
    issuedDate: text(x?.issuedDate),
    totalAmount: text(x?.totalAmount),
    vatAmount: text(x?.vatAmount),
    discountAmount: text(x?.discountAmount),
    items: (x?.items ?? []).map((item) => ({
      rawName: text(item.rawName),
      quantity: text(item.quantity),
      unitPrice: text(item.unitPrice),
      amount: text(item.amount),
    })),
  };
}

/**
 * 고친 필드만 모은다. 서버는 **보낸 필드만 바꾸고**, 안 바뀐 값을 보내면 수정 이력의 분자가
 * 부풀어 실사용 정확도가 실제보다 낮게 나온다(13.3). 값을 지우는 경로는 없어서 비운 칸은 따로 돌려준다.
 */
function diff(form: ReviewForm, x: Extraction): { patch: ExtractionPatch; cleared: string[] } {
  const patch: ExtractionPatch = {};
  const cleared: string[] = [];

  for (const { key, label } of TEXT_FIELDS) {
    const next = form[key].trim();
    const prev = x[key] ?? '';
    if (next === prev) continue;
    if (next === '') cleared.push(label);
    else patch[key] = next;
  }
  for (const { key, label } of MONEY_FIELDS) {
    const raw = form[key].trim();
    if (raw === '') {
      if (x[key] !== undefined) cleared.push(label);
      continue;
    }
    if (toNumber(raw) !== x[key]) patch[key] = toNumber(raw);
  }

  (x.items ?? []).forEach((item, i) => {
    const row = form.items[i];
    if (!row) return;
    const change: ExtractionItemPatch = { extractionItemId: item.extractionItemId };
    if (row.rawName.trim() !== (item.rawName ?? '')) {
      if (row.rawName.trim() === '') cleared.push(`${i + 1}번째 품목 이름`);
      else change.rawName = row.rawName.trim();
    }
    for (const key of ['quantity', 'unitPrice', 'amount'] as const) {
      const raw = row[key].trim();
      if (raw === '') {
        if (item[key] !== undefined) cleared.push(`${i + 1}번째 품목`);
        continue;
      }
      if (toNumber(raw) !== item[key]) change[key] = toNumber(raw);
    }
    if (Object.keys(change).length > 1) (patch.items ??= []).push(change);
  });

  return { patch, cleared: [...new Set(cleared)] };
}

export function EvidenceReviewScreen() {
  const params = useParams();
  const evidenceId = Number(params.evidenceId);
  const validId = Number.isInteger(evidenceId) && evidenceId > 0;
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [saved, setSaved] = useState('');

  const detail = useQuery({
    queryKey: ['v7', 'evidence', evidenceId],
    enabled: validId,
    queryFn: () => getEvidence(evidenceId),
    // 아직 읽는 중이면 다 읽을 때까지 다시 묻는다.
    refetchInterval: (q) => {
      const r = q.state.data;
      return r?.ready && r.ok && READING.includes(r.data.ocrStatus) ? 3000 : false;
    },
  });

  const result = detail.data;
  const evidence = result?.ready && result.ok ? result.data : undefined;
  const extraction = evidence?.extraction;
  const uploaded = evidence ? previews.get(evidence.evidenceId) : undefined;

  // 원본은 인증 헤더가 필요해 blob 으로 받는다. 파일은 바뀌지 않으니 한 번만 묻는다
  const original = useQuery({
    queryKey: ['v7', 'evidence-file', evidenceId],
    enabled: validId && !previews.has(evidenceId),
    staleTime: Infinity,
    queryFn: () => getEvidenceFile(evidenceId),
  });
  const file = original.data?.ready && original.data.ok ? original.data.data : undefined;
  const fileUrl = useMemo(() => (file ? URL.createObjectURL(file.blob) : undefined), [file]);
  useEffect(() => () => void (fileUrl && URL.revokeObjectURL(fileUrl)), [fileUrl]);
  const preview = uploaded ?? (fileUrl && file ? { url: fileUrl, type: file.blob.type } : undefined);
  const originalError =
    original.data?.ready && !original.data.ok
      ? original.data.error.code === 'EVIDENCE_FILE_MISSING'
        ? '원본 파일이 저장소에 없습니다. 같은 증빙을 다시 올려 주세요.'
        : `원본을 불러오지 못했습니다 — ${original.data.error.message}`
      : null;

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ReviewForm>({
    resolver: zodResolver(schema),
    mode: 'onTouched',
    values: toForm(extraction),
  });

  async function submit(form: ReviewForm, confirm: boolean) {
    if (!evidence || !extraction) return;
    setSaved('');
    const { patch, cleared } = diff(form, extraction);
    if (cleared.length > 0) {
      setError('root', {
        message: `${cleared.join(', ')} 값은 비울 수 없습니다. 원본에 없는 값이면 읽은 그대로 두세요.`,
      });
      return;
    }
    if (confirm) patch.confirmed = true;
    else if (Object.keys(patch).length === 0) {
      setSaved('고친 내용이 없습니다.');
      return;
    }

    const res = await patchEvidenceExtraction(evidence.evidenceId, patch);
    if (!res.ready) {
      setError('root', { message: '증빙 검토 기능이 아직 준비되지 않았습니다.' });
      return;
    }
    if (!res.ok) {
      setError('root', {
        message:
          res.status === 409
            ? '지금 상태에서는 고칠 수 없습니다. 아직 읽는 중이거나 읽지 못한 증빙입니다.'
            : `저장하지 못했습니다 — ${res.error.message}`,
      });
      return;
    }
    // 응답의 검산은 고친 값으로 다시 돌린 결과다. 상세는 응답으로 바꾸고 목록 정렬은 다시 읽는다.
    queryClient.setQueryData(['v7', 'evidence', evidenceId], res);
    queryClient.invalidateQueries({ queryKey: ['v7', 'evidences'] });
    setSaved(confirm ? '확정했습니다. 사장님이 확인한 값으로 기록됩니다.' : '고친 내용을 저장했습니다. 아직 확정 전입니다.');
  }

  // 종류는 추출 결과가 아니라 증빙 자체다 — 응답이 상세 전체라 그대로 캐시에 넣는다
  const changeType = useMutation({
    mutationFn: (evidenceType: EvidenceType) => patchEvidence(evidenceId, { evidenceType }),
    onSuccess: (res) => {
      if (!res.ready || !res.ok) return;
      queryClient.setQueryData(['v7', 'evidence', evidenceId], res);
      queryClient.invalidateQueries({ queryKey: ['v7', 'evidences'] });
    },
  });
  // 확인 대기(EXTRACTED)에서만 된다. 받은 뒤 PROCESSING 이 되어 위의 폴링이 다시 돈다
  const reExtract = useMutation({
    mutationFn: () => reExtractEvidence(evidenceId),
    onSuccess: () => {
      setSaved('');
      queryClient.invalidateQueries({ queryKey: ['v7', 'evidence', evidenceId] });
      queryClient.invalidateQueries({ queryKey: ['v7', 'evidences'] });
    },
  });
  // 소프트 삭제지만 되살리는 길이 없다. 거래 연결이 끊겨 그 거래는 증빙 누락으로 돌아갈 수 있다
  const remove = useMutation({
    mutationFn: () => deleteEvidence(evidenceId),
    onSuccess: (res) => {
      if (!res.ready || !res.ok) return;
      for (const key of [['v7', 'evidences'], ['v7', 'tax-review'], ['v7', 'tax-drafts'], ['v7', 'tax-draft'], ['transactions']]) {
        queryClient.invalidateQueries({ queryKey: key });
      }
      queryClient.removeQueries({ queryKey: ['v7', 'evidence', evidenceId] });
      navigate('/evidences');
    },
  });
  const manageError = [changeType.data, reExtract.data, remove.data]
    .map((r) => (!r ? null : !r.ready ? '아직 준비되지 않은 기능입니다.' : r.ok ? null : r.error.message))
    .find(Boolean);

  const confidence = (key: string) => {
    const v = extraction?.fieldConfidence?.[key];
    return typeof v === 'number' ? v : undefined;
  };
  const isLow = (key: string) => {
    const v = confidence(key);
    return v !== undefined && v < LOW_CONFIDENCE;
  };
  const lowHint = '확신이 낮게 읽힌 값입니다. 원본과 대조해 주세요.';
  const reasons = extraction?.validation?.reviewReasons ?? [];
  const needsReview = extraction?.validation?.needsReview;
  const items = extraction?.items ?? [];

  return (
    <PageShell
      title="증빙 검토"
      actions={
        <>
          {evidence && (
            <button
              type="button"
              className="v7-secondary-button"
              disabled={remove.isPending}
              onClick={() => {
                const linkedNote = evidence.linkedTransactionIds?.length ? ' 연결된 거래는 증빙 누락으로 돌아갈 수 있습니다.' : '';
                if (window.confirm(`이 증빙을 삭제할까요? 되돌릴 수 없습니다.${linkedNote}`)) remove.mutate();
              }}
            >
              <Icon name="close" size={15} /> {remove.isPending ? '삭제하는 중…' : '삭제'}
            </button>
          )}
          <Link className="v7-secondary-button" to="/evidences">
            증빙함으로
          </Link>
        </>
      }
    >
      {!validId ? (
        <EmptyState title="증빙을 찾을 수 없습니다" hint="증빙함에서 다시 골라 주세요." />
      ) : detail.isPending ? (
        <p className="v7-note">불러오는 중…</p>
      ) : !result?.ready ? (
        <EmptyState title="증빙 검토는 준비 중입니다" hint="기능이 열리면 바로 표시됩니다." />
      ) : !result.ok ? (
        <div className="v7-note v7-note--bad" role="alert">
          <Icon name="warning" size={18} />
          <div>
            <strong>증빙을 불러오지 못했습니다</strong>
            <span>{result.error.message}</span>
          </div>
        </div>
      ) : !evidence ? null : READING.includes(evidence.ocrStatus) ? (
        <div className="v7-note v7-note--wait" role="status">
          <Icon name="clock" size={18} />
          <div>
            <strong>AI가 읽고 있습니다 — {evidence.fileName}</strong>
            <span>다 읽으면 이 화면이 저절로 바뀝니다.</span>
          </div>
        </div>
      ) : evidence.ocrStatus === 'FAILED' || evidence.ocrStatus === 'SKIPPED' || !extraction ? (
        <div className="v7-note v7-note--bad" role="alert">
          <Icon name="warning" size={18} />
          <div>
            <strong>
              {evidence.ocrStatus === 'SKIPPED' ? '읽을 대상이 아닌 증빙입니다' : '읽지 못했습니다'} — {evidence.fileName}
            </strong>
            <span>사진이 흐리거나 잘리지 않았는지 확인하고 다시 올려 주세요.</span>
          </div>
        </div>
      ) : (
        <>
          <div className="v7-review">
            <Section title={`원본 · ${evidence.fileName}`}>
              <div className="v7-original">
                {preview ? (
                  preview.type === 'application/pdf' ? (
                    <iframe src={preview.url} title={`${evidence.fileName} 원본`} />
                  ) : (
                    <img src={preview.url} alt={`${evidence.fileName} 원본`} />
                  )
                ) : originalError ? (
                  <div className="v7-note v7-note--bad" role="alert">
                    <Icon name="warning" size={18} />
                    <span>{originalError}</span>
                  </div>
                ) : (
                  <p className="v7-note">원본을 불러오는 중…</p>
                )}
              </div>
            </Section>

            <Section title={`읽은 값 · ${EVIDENCE_TYPE[evidence.evidenceType] ?? evidence.evidenceType}`}>
              <div className="evidence-manage">
                <label>
                  증빙 종류
                  <select
                    value={evidence.evidenceType}
                    disabled={changeType.isPending}
                    onChange={(e) => changeType.mutate(e.target.value as EvidenceType)}
                  >
                    {(Object.keys(EVIDENCE_TYPE) as EvidenceType[]).map((t) => (
                      <option key={t} value={t}>
                        {EVIDENCE_TYPE[t]}
                      </option>
                    ))}
                  </select>
                </label>
                <button
                  type="button"
                  className="v7-secondary-button"
                  disabled={evidence.ocrStatus !== 'EXTRACTED' || reExtract.isPending}
                  onClick={() => reExtract.mutate()}
                >
                  <Icon name="refresh" size={15} /> {reExtract.isPending ? '요청하는 중…' : '다시 읽기'}
                </button>
                <small>
                  {evidence.ocrStatus === 'CONFIRMED'
                    ? '확정한 증빙은 다시 읽을 수 없습니다 — 확정한 값이 사라지기 때문입니다.'
                    : '틀리게 읽혔다면 더 꼼꼼하게 다시 읽습니다. 나아진다는 보장은 없고, 실패하면 지금 값이 남습니다.'}
                </small>
              </div>
              {manageError && (
                <p className="v7-inline-error" role="alert">
                  ⚠ {manageError}
                </p>
              )}
              {needsReview === true ? (
                <div className="v7-note v7-note--bad" role="status">
                  <Icon name="warning" size={18} />
                  <div>
                    <strong>먼저 확인할 곳</strong>
                    <ul className="v7-reasons">
                      {(reasons.length > 0 ? reasons : ['LOW_CONFIDENCE']).map((code) => (
                        <li key={code}>{REVIEW_REASON[code] ?? code}</li>
                      ))}
                    </ul>
                  </div>
                </div>
              ) : needsReview === false ? (
                <div className="v7-note v7-note--good">
                  <Icon name="check" size={18} />
                  <div>
                    <strong>읽은 숫자끼리는 맞습니다</strong>
                    <span>숫자가 맞아도 잘못 읽었을 수 있어 원본과 한 번 대조해 주세요.</span>
                  </div>
                </div>
              ) : null}

              <form
                className="v7-settlement-form v7-gap-form"
                onSubmit={handleSubmit((form) => submit(form, true))}
                noValidate
              >
                <div className="v7-form-grid v7-form-grid--two">
                  {TEXT_FIELDS.map(({ key, label, type }) => (
                    <div key={key} className={isLow(key) ? 'v7-field-low' : undefined}>
                      <Field label={label} error={errors[key]?.message} hint={isLow(key) ? lowHint : undefined}>
                        <input type={type} {...register(key)} />
                      </Field>
                    </div>
                  ))}
                </div>
                <div className="v7-form-grid">
                  {MONEY_FIELDS.map(({ key, label }) => (
                    <div key={key} className={isLow(key) ? 'v7-field-low' : undefined}>
                      <Field label={label} error={errors[key]?.message} hint={isLow(key) ? lowHint : undefined}>
                        <input type="text" inputMode="numeric" {...register(key)} />
                      </Field>
                    </div>
                  ))}
                </div>

                {items.length > 0 && (
                  <div className={isLow('items') ? 'v7-field-low' : undefined}>
                    <h3 className="v7-subhead">
                      품목 {items.length}개{isLow('items') && ' · 확신이 낮게 읽혔습니다'}
                    </h3>
                    <div className="tx-table-wrap v7-scroll" tabIndex={0} aria-label="읽은 품목">
                      <table className="tx-table v7-items">
                        <thead>
                          <tr>
                            <th scope="col">품목</th>
                            <th scope="col" className="tx-num">
                              수량
                            </th>
                            <th scope="col" className="tx-num">
                              단가
                            </th>
                            <th scope="col" className="tx-num">
                              금액
                            </th>
                          </tr>
                        </thead>
                        <tbody>
                          {items.map((item, i) => (
                            <tr key={item.extractionItemId}>
                              <td>
                                <input aria-label={`${i + 1}번째 품목 이름`} {...register(`items.${i}.rawName`)} />
                              </td>
                              <td>
                                <input
                                  aria-label={`${i + 1}번째 품목 수량`}
                                  inputMode="decimal"
                                  {...register(`items.${i}.quantity`)}
                                />
                              </td>
                              <td>
                                <input
                                  aria-label={`${i + 1}번째 품목 단가`}
                                  inputMode="numeric"
                                  {...register(`items.${i}.unitPrice`)}
                                />
                              </td>
                              <td>
                                <input
                                  aria-label={`${i + 1}번째 품목 금액`}
                                  inputMode="numeric"
                                  {...register(`items.${i}.amount`)}
                                />
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    {errors.items && <p className="v7-inline-error">품목 칸에 숫자가 아닌 값이 있습니다.</p>}
                  </div>
                )}

                <FormError message={errors.root?.message} />
                {saved && (
                  <p className="v7-note v7-note--good" role="status">
                    <Icon name="check" size={18} />
                    {saved}
                  </p>
                )}

                <div className="v7-review-actions">
                  <button type="submit" className="auth-submit" disabled={isSubmitting}>
                    {isSubmitting ? '저장하는 중…' : evidence.ocrStatus === 'CONFIRMED' ? '고친 값으로 다시 확정' : '이대로 확정'}
                  </button>
                  <button
                    type="button"
                    className="v7-secondary-button"
                    disabled={isSubmitting}
                    onClick={handleSubmit((form) => submit(form, false))}
                  >
                    확정하지 않고 저장
                  </button>
                </div>
              </form>

              <p className="v7-note v7-note--small">
                {extraction.modelName ? `읽은 모델 ${extraction.modelName}` : '읽은 모델 —'}
                {extraction.extractedAt && ` · 읽은 시각 ${formatDateTime(extraction.extractedAt)}`}
                {extraction.confirmedAt && ` · 확정 ${formatDateTime(extraction.confirmedAt)}`}
              </p>
            </Section>
          </div>
          <EvidenceLinkSection evidence={evidence} />
        </>
      )}
    </PageShell>
  );
}
