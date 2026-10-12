import { Link, useLocation } from 'react-router';
import '@/v7/page.css';
import '@/v7/mypage.css';

/**
 * 이용약관 · 개인정보처리방침. 가입 전에도 읽을 수 있어야 해서 로그인 없이 연다 —
 * 그래서 PageShell(사업장·프로필이 필요한 상단 바)을 쓰지 않는다.
 *
 * ponytail: 문안은 서비스 동작에 맞춘 초안이다. 정식 시행 전 법무 검토를 거쳐 교체한다.
 */
const EFFECTIVE_DATE = '2026-09-15 (초안)';

export function Policy() {
  const { hash } = useLocation();
  const tab = hash === '#privacy' ? 'privacy' : 'terms';

  return (
    <div className="v7-page">
      <header className="topbar">
        <div className="topbar-inner">
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
        </div>
      </header>

      <main id="main" className="v7-page__body policy">
        <header className="v7-page__head">
          <div>
            <h1>약관 및 정책</h1>
            <p>시행일 {EFFECTIVE_DATE}</p>
          </div>
        </header>

        <nav className="policy-tabs" aria-label="정책 문서">
          <Link to="#terms" aria-current={tab === 'terms' ? 'page' : undefined}>
            이용약관
          </Link>
          <Link to="#privacy" aria-current={tab === 'privacy' ? 'page' : undefined}>
            개인정보처리방침
          </Link>
        </nav>

        {tab === 'terms' ? <Terms /> : <Privacy />}
      </main>
    </div>
  );
}

function Terms() {
  return (
    <article className="v7-panel policy-doc" aria-labelledby="terms-title">
      <h2 id="terms-title">이용약관</h2>

      <h3>제1조 목적</h3>
      <p>
        이 약관은 잔고예보(이하 「서비스」)가 제공하는 사업용 계좌 잔액 예측, 정산 공백 알림, 거래·증빙 관리 기능의
        이용 조건과 절차를 정합니다.
      </p>

      <h3>제2조 서비스의 내용</h3>
      <ul>
        <li>연결한 계좌와 올린 거래 내역을 바탕으로 날짜별 예상 잔액과 정산 공백 가능성을 보여 줍니다.</li>
        <li>반복되는 입출금을 찾아 예정 거래로 제안하고, 사용자가 승인한 것만 예보에 반영합니다.</li>
        <li>영수증 등 증빙에서 금액·날짜를 읽어 제안하며, 사용자가 확인해야 확정됩니다.</li>
      </ul>

      <h3>제3조 예측 정보의 성격</h3>
      <p>
        서비스가 보여 주는 예상 잔액과 공백은 <strong>현재 데이터 기준의 추정 범위</strong>이며 확정된 금액이 아닙니다.
        세무 관련 값은 후보로만 제시하며 신고·납부의 최종 판단과 책임은 사용자에게 있습니다.
      </p>

      <h3>제4조 사용자의 승인</h3>
      <p>
        이체·결제일 변경·대출 신청처럼 돈이 움직이는 일은 사용자가 명시적으로 승인한 경우에만 이루어집니다.
        서비스가 사용자 몰래 실행하지 않습니다.
      </p>

      <h3>제5조 계정과 계좌</h3>
      <ul>
        <li>사용자는 본인 또는 본인이 운영하는 사업장의 계좌만 연결해야 합니다.</li>
        <li>계좌 연결은 1원 인증으로 확인하며, 언제든 마이페이지에서 연결을 해제할 수 있습니다.</li>
        <li>계정 정보를 타인과 공유해 생긴 문제는 사용자에게 책임이 있습니다.</li>
      </ul>

      <h3>제6조 서비스의 변경·중단</h3>
      <p>
        점검, 외부 금융망·AI 서비스 장애 등으로 일부 기능이 일시적으로 제한될 수 있습니다. 이때도 이미 저장된
        거래와 증빙은 보존합니다.
      </p>
    </article>
  );
}

function Privacy() {
  return (
    <article className="v7-panel policy-doc" aria-labelledby="privacy-title">
      <h2 id="privacy-title">개인정보처리방침</h2>

      <h3>1. 수집하는 정보</h3>
      <div className="policy-table-wrap">
        <table className="tx-table">
          <thead>
            <tr>
              <th scope="col">구분</th>
              <th scope="col">항목</th>
              <th scope="col">목적</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td data-label="구분">계정</td>
              <td data-label="항목">이메일, 비밀번호(암호화 저장), 이름, 휴대폰 번호(선택)</td>
              <td data-label="목적">로그인, 본인 확인, 알림</td>
            </tr>
            <tr>
              <td data-label="구분">사업장</td>
              <td data-label="항목">가게 이름, 업종, 과세 유형, 지역·개업일·사업자등록번호(선택)</td>
              <td data-label="목적">사업장별 예보, 세금 일정 계산</td>
            </tr>
            <tr>
              <td data-label="구분">계좌·거래</td>
              <td data-label="항목">은행, 계좌번호, 잔액, 거래 일시·금액·거래명</td>
              <td data-label="목적">잔액 예측, 반복 거래 탐지, 거래 분류</td>
            </tr>
            <tr>
              <td data-label="구분">증빙</td>
              <td data-label="항목">사용자가 올린 영수증·세금계산서 등 이미지와 읽어 낸 값</td>
              <td data-label="목적">증빙 관리, 신고 자료 준비</td>
            </tr>
          </tbody>
        </table>
      </div>

      <h3>2. 처리 위탁</h3>
      <ul>
        <li>계좌 인증과 거래 조회는 연동한 금융망을 통해 이루어집니다.</li>
        <li>
          거래 분류와 증빙 판독에는 외부 AI 분석 서비스를 씁니다. 이때 거래명·금액·증빙 이미지가 전달되며,
          분석 결과는 사용자가 확인하기 전까지 확정되지 않습니다.
        </li>
      </ul>

      <h3>3. 보관 기간과 파기</h3>
      <p>
        회원 탈퇴 시 지체 없이 파기합니다. 다만 관계 법령이 보존을 요구하는 정보는 그 기간 동안 분리해 보관한 뒤
        파기합니다.
      </p>

      <h3>4. 이용자의 권리</h3>
      <p>
        마이페이지에서 내 정보를 조회·수정하고 계좌 연결을 해제할 수 있습니다. 열람·정정·삭제·처리 정지를 요청하면
        지체 없이 조치합니다.
      </p>

      <h3>5. 안전성 확보 조치</h3>
      <ul>
        <li>비밀번호는 복호화할 수 없는 방식으로 저장합니다.</li>
        <li>로그인 토큰은 브라우저 저장소에 남기지 않고, 갱신 토큰은 HttpOnly 쿠키로만 주고받습니다.</li>
        <li>계좌번호는 화면에 가려서 보여 줍니다.</li>
      </ul>
    </article>
  );
}
