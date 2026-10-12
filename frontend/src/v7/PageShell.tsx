import type { ReactNode } from 'react';
import { useBusiness } from '@/business/useBusiness';
import { AppHeader } from './AppHeader';
import './page.css';

/**
 * v7 언어의 하위 화면 틀 — 계좌 · 거래처럼 대시보드가 아닌 화면이 쓴다.
 *
 * 메인(대시보드)과 같은 상단 바를 쓴다. 화면마다 다른 머리말을 두면
 * 같은 서비스로 안 읽힌다.
 */
export function PageShell({
  title,
  lede,
  showTitle = false,
  actions,
  children,
}: {
  /**
   * 이 화면의 이름. <b>화면에는 그리지 않는다</b> — 2차 메뉴 바가 어느 화면인지 이미
   * 말하고, 같은 말을 두 번 놓으면 첫 구획이 그만큼 아래로 밀린다. 낭독기에는 남긴다.
   */
  title?: string;
  /**
   * 제목 아래 한 문단. `showTitle` 일 때만 그린다.
   *
   * **`ReactNode` 인 이유** — 문장이 여럿이면 `<br />` 로 끊어 넘길 수 있어야 한다.
   * `max-width: 60ch` 에 걸려 접히는 자리와 문장 경계가 달라서, 문자열만 받으면 문장
   * 중간에서 줄이 바뀐다.
   */
  lede?: ReactNode;
  /**
   * 제목과 설명을 화면에도 그린다. 켜는 곳은 <b>제목이 화면 이름이 아니라 내용인
   * 화면</b>뿐이다 — 지원 제도 상세의 공고 제목과 주관 기관이 그렇다. 지우면 무슨 공고를
   * 보고 있는지 알 수 없다.
   */
  showTitle?: boolean;
  actions?: ReactNode;
  children: ReactNode;
}) {
  const { current } = useBusiness();

  return (
    <div className="v7-page">
      <AppHeader store={current?.name} />

      <main id="main" className="v7-page__body">
        {/* 화면 이름을 그리지 않되 <b>읽어 주기는 한다.</b> 라우트마다 탭 제목을 따로 두지
          * 않아 탭은 늘 「잔고예보 · 예보」다 — h1 까지 없으면 낭독기로는 어느 화면에
          * 들어왔는지 알 길이 없다. 제목 이동(H)으로 자리를 잡는 사람이 기댈 곳이다.
          *
          * `Section` 이 「제목을 화면에 보이지 않을 때는 label 만 넘긴다」로 쓰는 것과
          * 같은 수법이다. */}
        {title && !showTitle && <h1 className="v7-page__title">{title}</h1>}
        {((showTitle && (title || lede)) || actions) && (
          <header className="v7-page__head v7-rise">
            {showTitle && (title || lede) && (
              <div>
                {title && <h1>{title}</h1>}
                {lede && <p>{lede}</p>}
              </div>
            )}
            {actions && <div className="v7-page__actions">{actions}</div>}
          </header>
        )}
        {children}
      </main>
    </div>
  );
}

/** 구획. 제목을 화면에 보이지 않을 때는 label 만 넘긴다 — 읽어 주는 이름은 남긴다. */
export function Section({
  title,
  label,
  action,
  children,
}: {
  title?: string;
  label?: string;
  /** 제목 줄 오른쪽에 붙는 구획 단위 조작(예: 일괄 확인). 없으면 제목만 그대로 나온다. */
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="v7-panel v7-rise" aria-label={title ? undefined : label}>
      {title &&
        (action ? (
          <div className="v7-panel__head-row">
            <h2 className="v7-panel__head">{title}</h2>
            {action}
          </div>
        ) : (
          <h2 className="v7-panel__head">{title}</h2>
        ))}
      {children}
    </section>
  );
}
