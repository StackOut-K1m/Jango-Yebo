import { Link } from 'react-router';
import { PageShell } from './PageShell';
import { Icon } from './icons';
import { NAV_GROUPS } from './nav';
import './menu-screen.css';

/**
 * 좁은 화면의 「전체」 탭 (화면구성도 4.1).
 *
 * 목록은 상단 바와 <b>같은 `NAV_GROUPS`</b> 를 읽는다. 두 벌로 갈라지면 PC 에만 있는
 * 화면이 생긴다 — 메뉴를 늘릴 때 고칠 곳은 `nav.ts` 하나다.
 */
export function MenuScreen() {
  return (
    <PageShell title="전체 메뉴">
      <div className="m-menu">
        {NAV_GROUPS.map((group) => (
          <section key={group.label}>
            <h2>{group.label}</h2>
            <ul>
              {group.items.map((item) => (
                <li key={item.to}>
                  <Link to={item.to}>
                    <Icon name={item.icon} />
                    {item.label}
                    <Icon name="chevron" size={16} />
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        ))}

        {/* 메뉴 지도에 없는 화면들. 계좌는 마이페이지 안에 있다(nav.ts) */}
        <section>
          <h2>설정</h2>
          <ul>
            <li>
              <Link to="/mypage">
                <Icon name="settings" />
                마이페이지 · 계좌
                <Icon name="chevron" size={16} />
              </Link>
            </li>
          </ul>
        </section>
      </div>
    </PageShell>
  );
}
