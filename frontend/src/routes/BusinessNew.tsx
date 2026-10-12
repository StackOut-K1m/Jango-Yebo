import { createBusiness, type IndustryType, type TaxationType } from '@jangoyebo/core';
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { useLocation, useNavigate } from 'react-router';
import { z } from 'zod';
import { useBusiness } from '@/business/useBusiness';
import { applyFieldErrors } from '@/routes/fieldErrors';
import { AuthShell, Field, FormError } from '@/v7/AuthShell';

const INDUSTRIES: { value: IndustryType; label: string }[] = [
  { value: 'FOOD_RESTAURANT', label: '음식점' },
  { value: 'FOOD_CAFE', label: '카페' },
  { value: 'FOOD_BAKERY', label: '베이커리' },
  { value: 'FOOD_OTHER', label: '기타 요식업' },
  { value: 'RETAIL', label: '소매' },
  { value: 'SERVICE', label: '서비스' },
  { value: 'OTHER', label: '기타' },
];

const TAXATIONS: { value: TaxationType; label: string; hint: string }[] = [
  { value: 'GENERAL', label: '일반과세', hint: '연 매출 1억 400만원 이상' },
  { value: 'SIMPLIFIED', label: '간이과세', hint: '연 매출 1억 400만원 미만' },
];

const schema = z.object({
  name: z.string().min(1, '가게 이름을 입력해 주세요.'),
  industryType: z.string().min(1, '업종을 골라 주세요.'),
  taxationType: z.string().min(1, '과세 유형을 골라 주세요.'),
  region: z.string().optional(),
  openedAt: z.string().optional(),
  // 하이픈이 필수다(서버 검증). 빈 값은 통과시킨다 — 선택 입력이다.
  // union(.or)을 쓰면 실패 시 issue.path 가 비어 어느 칸의 오류인지 못 찾는다.
  businessRegNo: z
    .string()
    .optional()
    .refine((v) => !v || /^\d{3}-\d{2}-\d{5}$/.test(v), {
      message: '123-45-67890 형식으로 입력해 주세요.',
    }),
});
type Form = z.infer<typeof schema>;

export function BusinessNew() {
  const { businesses, refresh, select } = useBusiness();
  const navigate = useNavigate();
  const location = useLocation();
  // 첫 가게를 등록하는 중이면 돌아갈 화면이 없다. 주소로 바로 들어왔으면 앱 밖이 아니라 한눈에로 보낸다.
  const goBack =
    businesses.length > 0
      ? () => (location.key === 'default' ? navigate('/') : navigate(-1))
      : undefined;
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<Form>({
    resolver: zodResolver(schema),
    // 첫 검사는 칸을 벗어날 때, 그 뒤로는 고치는 동안 실시간으로 갱신한다.
    // onBlur 로 두면 오류를 고쳐도 다시 벗어나야 사라져서 답답하다.
    mode: 'onTouched',
    defaultValues: { industryType: 'FOOD_RESTAURANT', taxationType: 'GENERAL' },
  });

  // 검증은 zodResolver 가 이미 끝냈다. 여기 오면 값이 유효하다.
  async function onSubmit(values: Form) {

    const { name, industryType, taxationType, region, openedAt, businessRegNo } = values;
    const res = await createBusiness({
      name,
      industryType: industryType as IndustryType,
      taxationType: taxationType as TaxationType,
      ...(region ? { region } : {}),
      ...(openedAt ? { openedAt } : {}),
      ...(businessRegNo ? { businessRegNo } : {}),
    });

    if (!res.ready) {
      setError('root', { message: '사업장 등록 기능이 아직 준비되지 않았습니다.' });
      return;
    }
    if (!res.ok) {
      // 서버가 어느 칸이 틀렸는지 detail 에 담아 준다(공통규약 2.4).
      if (!applyFieldErrors(res.error, setError, ['name', 'industryType', 'taxationType', 'region', 'openedAt', 'businessRegNo'])) {
        setError('root', { message: res.error.message });
      }
      return;
    }

    await refresh();
    select(res.data.businessId);
    navigate(`/businesses/${res.data.businessId}/accounts`, { replace: true });
  }

  return (
    <AuthShell
      title="가게를 등록해 주세요"
      lede="가게마다 따로 예보합니다. 나중에 더 추가할 수 있어요."
      onBack={goBack}
    >
      <form onSubmit={handleSubmit(onSubmit)} noValidate>
        <Field label="가게 이름" error={errors.name?.message}>
          <input type="text" autoFocus placeholder="온기식당" {...register('name')} />
        </Field>

        <Field label="업종" error={errors.industryType?.message}>
          <select {...register('industryType')}>
            {INDUSTRIES.map((item) => (
              <option key={item.value} value={item.value}>
                {item.label}
              </option>
            ))}
          </select>
        </Field>

        <Field
          label="과세 유형"
          error={errors.taxationType?.message}
          hint="부가세 예수금과 세금 일정 계산에 씁니다"
        >
          <select {...register('taxationType')}>
            {TAXATIONS.map((item) => (
              <option key={item.value} value={item.value}>
                {item.label} · {item.hint}
              </option>
            ))}
          </select>
        </Field>

        <Field label="지역" error={errors.region?.message} hint="선택 · 지원 제도 추천에 씁니다">
          <input type="text" placeholder="광주 동구" {...register('region')} />
        </Field>

        <Field label="개업일" error={errors.openedAt?.message} hint="선택">
          <input type="date" {...register('openedAt')} />
        </Field>

        <Field
          label="사업자등록번호"
          error={errors.businessRegNo?.message}
          hint="선택 · 지원 제도 추천과 세무 자료에 씁니다"
        >
          <input
            type="text"
            inputMode="numeric"
            placeholder="123-45-67890"
            {...register('businessRegNo')}
          />
        </Field>

        <FormError message={errors.root?.message} />

        <button type="submit" className="auth-submit" disabled={isSubmitting}>
          {isSubmitting ? '등록하는 중…' : '등록하고 계좌 연결하기'}
        </button>
      </form>
    </AuthShell>
  );
}
