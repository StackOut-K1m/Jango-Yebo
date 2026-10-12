import { useEffect, useRef, useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router';
import { logout } from '@jangoyebo/core';
import { useAuth } from '@/auth/useAuth';
import { Icon } from './icons';
import { ThemeToggle } from './ThemeToggle';
import { NAV_GROUPS, groupFor } from './nav';
import { CardTonePicker } from './CardTonePicker';
import './header.css';

/**
 * 모든 화면이 쓰는 상단 바. 메인(App)과 하위 화면(PageShell)이 같은 메뉴를 보여야 같은
 * 서비스로 읽힌다. 메뉴 지도와 경로 판정은 `nav.ts` 한 곳에 있다.
 */
export function AppHeader({
  store,
  controls,
  profileExtra,
}: {
  store?: string;
  controls?: ReactNode;
  profileExtra?: ReactNode;
}) {
  const { me, setMe } = useAuth();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  /**
   * 메뉴 판이 펼쳐졌나. 묶음 하나가 아니라 **모든 묶음을 한꺼번에** 편다 — 옆 묶음에 무엇이
   * 있는지 보려고 버튼을 옮겨 다닐 필요가 없다. 각 목록은 제 버튼 바로 아래 열에 선다.
   */
  const [open, setOpen] = useState(false);
  const [profile, setProfile] = useState(false);
  const bar = useRef<HTMLDivElement>(null);
  /** hover 로 열고 닫는 예약. 바로 열면 스쳐 지나갈 때 번쩍이고, 바로 닫으면 메뉴로 내려가다 경계를 넘자마자 닫힌다. */
  const hoverTimer = useRef<number | undefined>(undefined);
  /**
   * 지금 열린 판을 hover 가 열었나. 마우스로 누를 때는 이미 hover 가 열어 둔 뒤라,
   * 클릭을 그대로 토글로 받으면 열린 것을 닫아 버린다 — 그때 첫 클릭은 열린 채로 둔다.
   */
  const hoverOpened = useRef(false);

  useEffect(() => () => window.clearTimeout(hoverTimer.current), []);

  useEffect(() => {
    const escape = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      setOpen(false);
      setProfile(false);
    };
    const outside = (e: PointerEvent) => {
      if (bar.current?.contains(e.target as Node)) return;
      setOpen(false);
      setProfile(false);
    };
    window.addEventListener('keydown', escape);
    window.addEventListener('pointerdown', outside);
    return () => {
      window.removeEventListener('keydown', escape);
      window.removeEventListener('pointerdown', outside);
    };
  }, []);

  async function onLogout() {
    await logout();
    setMe(null);
    navigate('/login', { replace: true });
  }

  /** 지금 화면이 속한 묶음. 2차 바는 이 묶음의 형제들을 펼친다. */
  const here = groupFor(pathname);

  return (
    <>
      {/* 판이 헤더 아래로 내려가 있어도 헤더의 자식이라, 목록에서 판의 빈 곳으로 내려가도 떠난 것이 아니다. */}
      <header
        className={`topbar ${open ? 'is-menu-open' : ''}`}
        onPointerEnter={(event) => {
          if (event.pointerType === 'mouse' && open) window.clearTimeout(hoverTimer.current);
        }}
        onPointerLeave={(event) => {
          if (event.pointerType !== 'mouse') return;
          window.clearTimeout(hoverTimer.current);
          hoverTimer.current = window.setTimeout(() => setOpen(false), 150);
        }}
      >
        <div className="topbar-inner" ref={bar}>
          <Link className="brand-button" to="/" aria-label="잔고예보 메인">
            <span className="brand">
              <span className="brand-symbol">
                <i />
                <i />
              </span>
              <span>
                잔고예보
                <span className="brand-en">JANGO YEBO</span>
              </span>
            </span>
          </Link>

          <nav className="main-nav" aria-label="상단 메뉴">
            {NAV_GROUPS.map((group) => {
              const active = group === here;
              return (
                <div
                  className="nav-group"
                  key={group.label}
                  // 마우스만 받는다. 터치는 누를 때 hover 이벤트도 같이 와서 열리자마자 닫힌다.
                  onPointerEnter={(event) => {
                    if (event.pointerType !== 'mouse' || open) return;
                    window.clearTimeout(hoverTimer.current);
                    hoverTimer.current = window.setTimeout(() => {
                      hoverOpened.current = true;
                      setProfile(false);
                      setOpen(true);
                    }, 80);
                  }}
                  // 열리기 전에 버튼을 벗어나면 스쳐 지나간 것이다. 닫는 것은 헤더가 맡는다.
                  onPointerLeave={(event) => {
                    if (event.pointerType === 'mouse' && !open) window.clearTimeout(hoverTimer.current);
                  }}
                >
                  <button
                    type="button"
                    className={active ? 'active' : ''}
                    aria-expanded={open}
                    onClick={() => {
                      window.clearTimeout(hoverTimer.current);
                      setProfile(false);
                      if (open && hoverOpened.current) {
                        hoverOpened.current = false;
                        return;
                      }
                      hoverOpened.current = false;
                      setOpen(!open);
                    }}
                  >
                    {group.label}
                  </button>
                  {/* 칸 폭을 잡는 보이지 않는 목록. 판이 닫혀 있어도 칸이 목록만큼 넓어야, 펼쳤을 때
                    * 목록이 제 이름 아래 서면서 옆 목록과 겹치지 않는다. 항목이 늘면 저절로 따라간다. */}
                  <span className="nav-sizer" aria-hidden="true">
                    {group.items.map((item) => (
                      <span key={item.to}>{item.label}</span>
                    ))}
                  </span>
                  {open && (
                    <div className="nav-menu">
                      {group.items.map((item) => (
                        // 지금 화면을 다시 누르면 화면이 바뀌지 않으니 여기서 접는다
                        <NavLink
                          key={item.to}
                          to={item.to}
                          end={item.to === '/'}
                          onClick={() => setOpen(false)}
                        >
                          <Icon name={item.icon} size={16} />
                          {item.label}
                        </NavLink>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
          </nav>

          <div className="header-actions">
            {/* 화면별 조작(예보 새로고침·사업장 전환). 팝오버 상태가 화면 쪽에 있어 통째로 받는다. */}
            {controls}
            {store && <span className="v7-page__store">{store}</span>}
            <div className="popover-anchor">
              <button
                className="avatar"
                aria-label={`${me?.name ?? '사장님'} 프로필`}
                aria-expanded={profile}
                onClick={() => {
                  setOpen(false);
                  setProfile(!profile);
                }}
              >
                {(me?.name ?? '?').slice(0, 1)}
              </button>
              {profile && (
                <div className="header-popover profile-popover">
                  <strong>{me?.name ?? '사장님'}</strong>
                  <span>{me?.email}</span>
                  <ThemeToggle />
                  <CardTonePicker />
                  <button onClick={() => navigate('/mypage')}>
                    <Icon name="settings" size={17} /> 마이페이지
                  </button>
                  {profileExtra}
                  <button onClick={onLogout}>
                    <Icon name="close" size={17} /> 로그아웃
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>
        {/* 목록 뒤에 까는 전체 폭 띠. 높이는 가장 긴 목록에 맞춘다. */}
        {open && (
          <div
            className="nav-board"
            aria-hidden="true"
            style={{ '--rows': Math.max(...NAV_GROUPS.map((group) => group.items.length)) } as CSSProperties}
          />
        )}
      </header>

      {/* 2차 메뉴 바. 1차는 묶음 이름만 보여 주고 눌러야 안이 열린다 — 들어온 뒤에는 형제
        * 화면이 사라져 옆으로 옮기려면 묶음을 다시 펼쳐야 했다. 지금 묶음을 펼쳐 둔 줄이다. */}
      {here && (
        <nav className="sub-nav" aria-label={`${here.label} 하위 메뉴`}>
          <div className="sub-nav-inner">
            {here.items.map((item) => (
              <NavLink key={item.to} to={item.to} end={item.to === '/'}>
                {item.label}
              </NavLink>
            ))}
        </div>
        </nav>
      )}
    </>
  );
}
