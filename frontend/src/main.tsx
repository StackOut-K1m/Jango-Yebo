import { configureCore, createMemoryTokenStore } from '@jangoyebo/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router';
import { AuthProvider } from '@/auth/AuthProvider';
import { RequireAuth } from '@/auth/RequireAuth';
import { BusinessGate } from '@/business/BusinessGate';
import { RequireBusiness } from '@/business/RequireBusiness';
import { AccountList } from '@/routes/AccountList';
import { BusinessNew } from '@/routes/BusinessNew';
import { V7App } from '@/v7/App';
import { CashGapScreen } from '@/v7/screens';
import { EvidenceReviewScreen, EvidenceScreen } from '@/v7/EvidenceScreen';
import { MobileShell } from '@/v7/MobileShell';
import { MenuScreen } from '@/v7/MenuScreen';
import { PatternScreen } from '@/v7/PatternScreen';
import { SafetyScreen } from '@/v7/SafetyScreen';
import { LoanScreen } from '@/v7/LoanScreen';
import { SupportDetailScreen, SupportScreen } from '@/v7/SupportScreen';
import { TaxReviewScreen } from '@/v7/TaxReviewScreen';
import { HometaxChecklistScreen } from '@/v7/HometaxChecklist';
import { TaxDraftDetailScreen, TaxDraftListScreen } from '@/v7/TaxDraftScreen';
import { ScenarioScreen } from '@/v7/ScenarioScreen';
import { SettlementScreen } from '@/v7/SettlementScreen';
import { Login } from '@/routes/Login';
import { MyPage } from '@/routes/MyPage';
import { Policy } from '@/routes/Policy';
import { Signup } from '@/routes/Signup';
import { TransactionList } from '@/routes/TransactionList';
import '@/styles/tokens.css';
import '@/styles/base.css';
// 새 화면용 유틸리티. 토큰 뒤에 와야 @theme inline 이 var 를 찾는다.
import '@/styles/tailwind.css';
// v7 화면 전용 스타일. .app 안쪽에만 걸리므로 기존 화면과 섞이지 않는다.
import '@/v7/styles.css';
import '@/v7/weather.css';
// 밝은 배합의 카드·폼 덧칠. styles.css 안에서 @import 하면 개발 서버가 경로를 프로젝트 루트 기준으로 찾아 깨진다.
import '@/v7/light-palette.css';
// 지갑 카드 색 커스텀. styles.css 의 카드 규칙과 특정도가 같아 순서로 이긴다 — 맨 마지막.
import '@/v7/card-tone.css';

// core는 플랫폼을 모른다. baseUrl·토큰 저장소·배포 여부를 여기서 넣어 준다.
configureCore({
  baseUrl: import.meta.env.VITE_API_BASE_URL,
  isProduction: import.meta.env.PROD,
  // Access Token은 메모리에만 둔다. localStorage 저장은 권장되지 않는다(공통규약 2.1).
  tokenStore: createMemoryTokenStore(),
  // Refresh Token은 HttpOnly 쿠키로 오간다. 브라우저가 알아서 싣는다.
  refreshMode: 'cookie',
});

const queryClient = new QueryClient();

const root = document.getElementById('root');
if (!root) throw new Error('#root 엘리먼트를 찾을 수 없습니다.');

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route path="/signup" element={<Signup />} />
            {/* 가입 전에도 읽는다 — 로그인 게이트 밖에 둔다 */}
            <Route path="/policy" element={<Policy />} />
            <Route element={<RequireAuth />}>
              <Route element={<BusinessGate />}>
                <Route path="/businesses/new" element={<BusinessNew />} />
                <Route path="/businesses/:businessId/accounts" element={<AccountList />} />
                {/* 사업장이 없으면 등록부터 시킨다 */}
                <Route element={<RequireBusiness />}>
                  {/* 좁은 화면의 하단 탭을 여기 한 곳에서 덮는다. 화면 코드는 건드리지 않는다 */}
                  <Route element={<MobileShell />}>
                  {/* 팀이 고른 v7 시안이 메인이다. */}
                  <Route path="/" element={<V7App />} />
                  {/* 잔액 예보는 메인으로 합쳤다. 옛 주소·북마크는 메인으로 보낸다 */}
                  <Route path="/forecast" element={<Navigate to="/" replace />} />
                  <Route path="/mypage" element={<MyPage />} />
                  <Route path="/gaps" element={<CashGapScreen />} />
                  <Route path="/patterns" element={<PatternScreen />} />
                  <Route path="/safety" element={<SafetyScreen />} />
                  <Route path="/scenarios" element={<ScenarioScreen />} />
                  <Route path="/settlements" element={<SettlementScreen />} />
                  <Route path="/transactions" element={<TransactionList />} />
                  <Route path="/evidences" element={<EvidenceScreen />} />
                  <Route path="/evidences/:evidenceId" element={<EvidenceReviewScreen />} />
                  <Route path="/loans" element={<LoanScreen />} />
                  <Route path="/support-programs" element={<SupportScreen />} />
                  <Route path="/support-programs/:programId" element={<SupportDetailScreen />} />
                  <Route path="/tax/review" element={<TaxReviewScreen />} />
                  <Route path="/tax/drafts" element={<TaxDraftListScreen />} />
                  <Route path="/tax/drafts/:draftId" element={<TaxDraftDetailScreen />} />
                  <Route path="/tax/drafts/:draftId/hometax" element={<HometaxChecklistScreen />} />
                  {/* 좁은 화면의 「전체」 탭. 넓은 화면에서는 상단 바가 같은 일을 한다 */}
                  <Route path="/menu" element={<MenuScreen />} />
                  </Route>
                </Route>
              </Route>
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
