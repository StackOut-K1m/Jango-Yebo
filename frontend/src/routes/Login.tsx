import { login } from '@jangoyebo/core';
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { Link, useLocation, useNavigate } from 'react-router';
import { z } from 'zod';
import { useAuth } from '@/auth/useAuth';
import { AuthShell, FormError, Field } from '@/v7/AuthShell';

// 폼 입력에는 zod를 쓴다. API 응답에는 쓰지 않는다(CLAUDE.md).
const schema = z.object({
  email: z.email('이메일 형식이 아닙니다.'),
  password: z.string().min(1, '비밀번호를 입력해 주세요.'),
});
type Form = z.infer<typeof schema>;

export function Login() {
  const { setMe } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/';

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

    const res = await login(values);

    if (!res.ready) {
      setError('root', { message: '로그인 기능이 아직 준비되지 않았습니다.' });
      return;
    }
    if (!res.ok) {
      // 어느 쪽이 틀렸는지 알려주지 않는다 — 계정 존재 여부가 드러난다.
      setError('root', { message: '이메일 또는 비밀번호를 다시 확인해 주세요.' });
      return;
    }

    // 로그인 응답의 user는 userId·name 뿐이라 전체 정보를 다시 받는다.
    const { getMe } = await import('@jangoyebo/core');
    const meRes = await getMe();
    setMe(meRes.ready && meRes.ok ? meRes.data : null);
    navigate(from, { replace: true });
  }

  return (
    <AuthShell title="다시 오셨네요" lede="가게의 돈 흐름을 이어서 확인하세요.">
      <form onSubmit={handleSubmit(onSubmit)} noValidate>
        <Field label="이메일" error={errors.email?.message}>
          <input type="email" autoComplete="email" autoFocus {...register('email')} />
        </Field>
        <Field label="비밀번호" error={errors.password?.message}>
          <input type="password" autoComplete="current-password" {...register('password')} />
        </Field>

        <FormError message={errors.root?.message} />

        <button type="submit" className="auth-submit" disabled={isSubmitting}>
          {isSubmitting ? '확인하는 중…' : '로그인'}
        </button>
      </form>

      <p className="auth-alt">
        아직 계정이 없으신가요? <Link to="/signup">회원가입</Link>
      </p>
    </AuthShell>
  );
}
