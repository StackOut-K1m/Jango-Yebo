import { login, signup } from '@jangoyebo/core';
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router';
import { z } from 'zod';
import { useAuth } from '@/auth/useAuth';
import { applyFieldErrors } from '@/routes/fieldErrors';
import { AuthShell, Field, FormError } from '@/v7/AuthShell';

const schema = z.object({
  email: z.email('이메일 형식이 아닙니다.'),
  // 서버 규칙과 같게 맞춘다 — 화면에서 통과하고 서버에서 거부되면 이유를 알기 어렵다.
  password: z
    .string()
    .min(8, '8자 이상 64자 이하로 정해 주세요.')
    .max(64, '8자 이상 64자 이하로 정해 주세요.')
    .refine((v) => /[A-Za-z]/.test(v) && /\d/.test(v) && /[^A-Za-z0-9]/.test(v), {
      message: '영문·숫자·특수문자를 모두 넣어 주세요.',
    }),
  name: z.string().min(1, '이름을 입력해 주세요.'),
  // union(.or)을 쓰면 실패 시 issue.path 가 비어 어느 칸의 오류인지 못 찾는다.
  phone: z
    .string()
    .optional()
    .refine((v) => !v || /^01[016789]-?\d{3,4}-?\d{4}$/.test(v), {
      message: '휴대폰 번호 형식이 아닙니다.',
    }),
});
type Form = z.infer<typeof schema>;

export function Signup() {
  const { setMe } = useAuth();
  const navigate = useNavigate();
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
  });

  // 검증은 zodResolver 가 이미 끝냈다. 여기 오면 값이 유효하다.
  async function onSubmit(values: Form) {

    const { email, password, name, phone } = values;
    const res = await signup({ email, password, name, ...(phone ? { phone } : {}) });

    if (!res.ready) {
      setError('root', { message: '회원가입 기능이 아직 준비되지 않았습니다.' });
      return;
    }
    if (!res.ok) {
      if (res.error.code === 'EMAIL_ALREADY_EXISTS') {
        setError('email', { message: '이미 가입된 이메일입니다.' });
        return;
      }
      // 서버가 어느 칸이 틀렸는지 detail 에 담아 준다(공통규약 2.4). 해당 칸에 붙인다.
      if (!applyFieldErrors(res.error, setError, ['email', 'password', 'name', 'phone'])) {
        setError('root', { message: res.error.message });
      }
      return;
    }

    // 가입 직후 바로 로그인시킨다. 방금 넣은 정보를 또 입력하게 하지 않는다.
    const loginRes = await login({ email, password });
    if (loginRes.ready && loginRes.ok) {
      const { getMe } = await import('@jangoyebo/core');
      const meRes = await getMe();
      setMe(meRes.ready && meRes.ok ? meRes.data : null);
      navigate('/', { replace: true });
      return;
    }
    navigate('/login', { replace: true });
  }

  return (
    <AuthShell title="가게의 흐름을 예보합니다" lede="계좌를 연결하면 앞으로의 잔고가 보입니다.">
      <form onSubmit={handleSubmit(onSubmit)} noValidate>
        <Field label="이메일" error={errors.email?.message}>
          <input type="email" autoComplete="email" autoFocus {...register('email')} />
        </Field>
        <Field
          label="비밀번호"
          error={errors.password?.message}
          hint="8~64자 · 영문·숫자·특수문자를 모두 포함"
        >
          <input type="password" autoComplete="new-password" {...register('password')} />
        </Field>
        <Field label="이름" error={errors.name?.message}>
          <input type="text" autoComplete="name" {...register('name')} />
        </Field>
        <Field label="휴대폰 번호" error={errors.phone?.message} hint="선택">
          <input type="tel" autoComplete="tel" {...register('phone')} />
        </Field>

        <FormError message={errors.root?.message} />

        <p className="auth-alt">
          가입 전에 <Link to="/policy#terms">이용약관</Link>과{' '}
          <Link to="/policy#privacy">개인정보처리방침</Link>을 확인해 주세요.
        </p>

        <button type="submit" className="auth-submit" disabled={isSubmitting}>
          {isSubmitting ? '가입하는 중…' : '가입하고 시작하기'}
        </button>
      </form>

      <p className="auth-alt">
        이미 계정이 있으신가요? <Link to="/login">로그인</Link>
      </p>
    </AuthShell>
  );
}
