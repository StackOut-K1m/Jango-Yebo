# API 명세서

# Jango-Yebo(잔고예보) — 소상공인 정산 공백 예측·운영 지원 서비스 API 명세서

| 항목 | 내용 |
| --- | --- |
| 문서 버전 | **v1.28** (v1.27 + **재료비 변화 What-if 제외** — 11.1 파라미터 9종 → 8종) |
| 작성일 | 2026-08-31 |
| 작성자 | SSAFY 광주 1반 C105 |
| 기준 문서 | 기능명세서_SRS v1.8, `docs/예측엔진_설계노트.md` v2, 기능 명세서(P0/P1/P2), 소상공인_정산공백_예측서비스_기획정리본, 기획_확장기능_통합정리, SSAFY 교육용 금융망 API 인터페이스 예제 |
| API 버전 | v1 |
| 문서 범위 | P0 코어 MVP 13개 + P1 우선 확장 18개 기능의 외부 API 및 내부 AI 워커 API |

---

## 문서 구성

개발 시 참조 효율을 위해 API 명세서 v1.2를 도메인별 파일로 분리했다. **장·절 번호는 분할 전 단일 문서와 동일하게 유지**하므로, 다른 문서(ERD 등)의 "API 11.1" 같은 절 참조는 파일명의 장 번호로 찾는다. 이 파일에는 기능 매핑(3장)·화면 매핑(4장)·전체 API 목록(5장)·변경 이력이 있다.

| 파일 | 장 | 내용 |
| --- | --- | --- |
| [00_공통규약.md](./00_공통규약.md) | 1·2·21·22·23 | 설계 원칙, 공통 규약(인증·Envelope·Job·멱등성), 권한, 오류 코드, 구현 전 확인 사항 |
| [06_인증_사용자.md](./06_인증_사용자.md) | 6 | 회원가입·로그인·토큰·프로필 |
| [07_사업장_계좌_수집.md](./07_사업장_계좌_수집.md) | 7 | 사업장, 계좌 연결·1원 인증, 금융망 동기화·CSV 업로드, 은행코드 |
| [08_거래_분류.md](./08_거래_분류.md) | 8 | 거래 카테고리·목록·수정, LLM 분류 작업, 거래처 사전 |
| [09_반복_예정거래.md](./09_반복_예정거래.md) | 9 | 반복 패턴 승인·수정, 예정 거래, 실제 거래 매칭 |
| [10_잔액예측_알림.md](./10_잔액예측_알림.md) | 10 | 잔액 곡선, 정산 공백, 인앱 알림, 예측 정확도, 계절성·예측 옵션 |
| [11_시나리오.md](./11_시나리오.md) | 11 | What-if 시나리오 생성·저장·조회 (P0-09·P0-13) |
| [12_안전선_준비금_부가세.md](./12_안전선_준비금_부가세.md) | 12 | 운영자금 안전선, 가상 준비금·오늘 모아둘 돈, 부가세 예수금 |
| [13_증빙_품목.md](./13_증빙_품목.md) | 13 | 증빙 업로드·OCR 추출·거래 매칭, 품목 가계부 |
| [14_정산_내보내기.md](./14_정산_내보내기.md) | 14 | 정산 수동 대사, 종합소득세 신고서 초안(14.3), 신고 준비자료 내보내기, **상품별 매출 구성비 지표(14.4~14.7, P1-22)** |
| [15_캘린더_리포트_대시보드.md](./15_캘린더_리포트_대시보드.md) | 15 | 자금·세금 캘린더, AI 브리핑, 월별 리포트, 매장용·다사업장 대시보드 |
| [16_대출_신용.md](./16_대출_신용.md) | 16 | 대출·마이너스통장 현황·동기화, 신용등급, 대출 상품 추천 |
| [17_지원제도_문서.md](./17_지원제도_문서.md) | 17 | 지원 제도 검색·추천, 서류 발급처 사전 (17.5 AI 문서 편집기는 v1.5 결번) |
| — | 18 | 결번 — 발주 보조가 P2(P2-14·P2-15)로 이관되어 범위 제외. 정의는 단일 문서 v1.1(git 이력) 참고 |
| [19_다사업장_자금융통.md](./19_다사업장_자금융통.md) | 19 | 다사업장 통합 대시보드, 매장 간 자금 융통 시뮬레이션 |
| [20_내부_AI워커.md](./20_내부_AI워커.md) | 20 | 내부 수집 배치·AI 워커 API |
| **[96_AI_백엔드_합의_거래분류.md](./96_AI_백엔드_합의_거래분류.md)** | 8·20 | **②③④ 결정 완료 · ① 채택 (2026-09-11).** 거래 분류(20.3)를 백엔드에 잇기 위한 결정 4건 — 배치 정책 · `allowedCategories` · `merchantName` 저장 위치 · 트리거. ①은 `1 Job ↔ 1 콜백` 전제를 놓쳐 워커가 청킹을 맡는 것으로 정정됐다 |
| **[97_AI_백엔드_합의_매출인쇄물.md](./97_AI_백엔드_합의_매출인쇄물.md)** | 14·20 | **결정 완료 (2026-09-11).** 매출 인쇄물(20.9) 저장 7건 — ①~⑦ 채택해 `V8__product_sales.sql` 로 반영됐다. ①(`gross_amount NOT NULL` 이 58mm 인쇄물 33.3%를 반려)은 NN 해제 + 구성비 분모 CHECK 으로 닫았다 |
| **[98_AI_백엔드_합의.md](./98_AI_백엔드_합의.md)** | 13·20 | **결정 완료 — 8건 전부 닫혔다.** 증빙 추출 결과 저장 ①~⑤·⑦ 은 `V6__evidence_extraction.sql`(2026-09-09), ⑥(P1-02 완료 기준)은 **「필드 강조」로 확정**(2026-09-11), ⑧-2(파일 퍼미션)는 인프라가 default ACL 로 닫았다 |
| **[99_구현현황.md](./99_구현현황.md)** | — | **현재 호출 가능한 엔드포인트와 명세 차이. 프론트엔드는 이 문서를 먼저 본다** |

---

## 3. 기능 ↔ API 매핑

| 기능 ID | 기능 | 대표 API |
| --- | --- | --- |
| P0-01 | 계좌 거래내역 수집 | `POST /businesses/{businessId}/accounts`, `POST /accounts/{accountId}/sync`, `POST /accounts/{accountId}/transactions/import` |
| P0-02 | 입출금 거래 분류 | `GET /businesses/{businessId}/transactions`, `PATCH /transactions/{transactionId}` |
| P0-03 | 반복 거래 탐지 | `GET /businesses/{businessId}/recurring-patterns?status=CANDIDATE` |
| P0-04 | 다음 입출금 추천 | `GET /recurring-patterns/{patternId}` (`nextExpected`) |
| P0-05 | 사용자 확인·수정 | `POST /recurring-patterns/{patternId}/confirm`, `/dismiss`, `PATCH /recurring-patterns/{patternId}` |
| P0-06 | 일회성 거래 등록 | `POST /businesses/{businessId}/planned-transactions` |
| P0-07 | 예상 잔액 계산 | `GET /businesses/{businessId}/forecast/balance`, `GET/PATCH /businesses/{businessId}/forecast-settings`(모델 토글), *(내부)* `POST /internal/v1/ai/forecast-residual` |
| P0-08 | 자금 부족 알림 | `GET /businesses/{businessId}/forecast/cash-gaps`, `GET /businesses/{businessId}/alerts` |
| P0-09 | 기본 시나리오 분석 | `POST /businesses/{businessId}/scenarios` |
| P0-10 | 증빙 관리 | `POST /businesses/{businessId}/evidences`, `POST /evidences/{evidenceId}/link` |
| P0-11 | 정산 수동 대사 | `POST /businesses/{businessId}/settlements` |
| P0-12 | 종합소득세 신고서 초안 자동 채움·내보내기 | `POST /businesses/{businessId}/tax-review/bulk`, `POST /businesses/{businessId}/tax-drafts`, `GET /tax-drafts/{draftId}`, `POST /tax-drafts/{draftId}/export`, `POST /businesses/{businessId}/exports`(부속 패키지) |
| P0-13 | What-if 시뮬레이션 고도화 | `POST /businesses/{businessId}/scenarios` (파라미터 조합·저장), `GET /scenarios/{scenarioId}` |
| P1-01 | LLM 거래 적요 자동 분류 | `POST /businesses/{businessId}/classification-jobs`, `GET /businesses/{businessId}/classification-jobs/current`, `GET /businesses/{businessId}/merchants` |
| P1-02 | 멀티모달 증빙 인식·매칭 | `GET /businesses/{businessId}/evidences`, `GET /evidences/{evidenceId}` (`extraction`), `PATCH /evidences/{evidenceId}/extraction`, `GET /evidences/{evidenceId}/match-candidates` |
| P1-03 | 영수증 품목 가계부 | `GET /businesses/{businessId}/items`, `GET /items/{itemId}/purchases`, `GET /businesses/{businessId}/item-purchases` |
| P1-04 | 영업일·명절 정산 지연 | `RecurringPattern.holidayRule`, `PlannedTransaction.holidayAdjusted`, 캘린더·예측 응답의 보정 표시 |
| P1-05 | 예측값 vs 실제값 | `GET /businesses/{businessId}/forecast/accuracy`(모델 비교 포함), `POST /planned-transactions/{plannedTransactionId}/match`, *(내부, 조건부)* `POST /internal/v1/ai/settlement-delay` |
| P1-06 | (결번) | SRS v1.10 에서 범위 제외 — 원자재 가격 지표. 재료비 변화 What-if 도 v1.28 에서 함께 뺐다 |
| P1-07 | 계절성·성수기 반영 | `GET /businesses/{businessId}/forecast/seasonality`, `PATCH /businesses/{businessId}/forecast-settings` |
| P1-08 | 운영자금 안전선 | `PUT /businesses/{businessId}/safety-line`, `GET /businesses/{businessId}/safety-line/recommendations` |
| P1-09 | 가상 준비금 | `POST /businesses/{businessId}/reserves`, `GET /businesses/{businessId}/reserves/daily-plan` |
| P1-11 | 부가세 예수금·준비금 | `GET /businesses/{businessId}/vat/status` |
| P1-12 | 다사업장·다계좌·자금 융통 | `GET /users/me/dashboard`, `POST /users/me/transfer-simulations` |
| P1-13 | 대출 현황 통합 | `GET /businesses/{businessId}/loans`, `POST /businesses/{businessId}/loans/sync` |
| P1-14 | 신용 기반 대출 추천 | `GET /businesses/{businessId}/credit`, `GET /businesses/{businessId}/loan-products`, 시나리오 `LOAN_EXECUTION` |
| P1-15 | 지원 제도 추천·공고 분석 | `GET /support-programs`, `GET /businesses/{businessId}/support-programs/recommendations`, `GET /document-guides` |
| P1-16 | (결번 — v1.5 범위 제외) | 지원사업 문서 AI 편집기. 발급처 링크는 P1-15로 흡수 |
| P1-17 | 주간/월간 AI 브리핑 | `GET /businesses/{businessId}/briefings` |
| P1-18 | 수입·지출·세금 캘린더 | `GET /businesses/{businessId}/calendar`, `GET /businesses/{businessId}/tax-schedules` |
| P1-19 | 월별 지출·수익 피드백 | `GET /businesses/{businessId}/monthly-reports/{month}` |
| P1-22 | 상품별 매출 구성비 | `POST`·`GET /businesses/{businessId}/product-sales`, `GET .../{productKey}`, `PATCH .../rename`, `POST .../ocr` |
| P1-23 | 신고자료 검토·확정 화면 | `GET /businesses/{businessId}/transactions?taxReviewStatus=`, `POST /businesses/{businessId}/tax-review/bulk` |

---

## 4. 화면 ↔ API 매핑

| 화면 | API |
| --- | --- |
| SC-01 로그인·사업장 선택 | Auth API, `GET /businesses`, `GET /users/me` |
| SC-02 매장용 오늘 요약 | `GET /businesses/{businessId}/dashboard` |
| SC-03 분석 대시보드 | `GET /businesses/{businessId}/forecast/balance`, `/forecast/cash-gaps`, `/alerts`, `GET /users/me/dashboard` |
| SC-04 거래내역·분류 | Transaction API, Merchant API, `POST /evidences/{evidenceId}/link` |
| SC-05 반복 거래·예측 | Recurring Pattern API, Planned Transaction API, `GET /businesses/{businessId}/forecast/accuracy` |
| SC-06 시나리오 분석 | Scenario API |
| SC-07 증빙·신고자료 | Evidence API, Item API, Settlement API, Tax Draft API(14.3), Export API |
| SC-08 자금·세금 캘린더 | `GET /businesses/{businessId}/calendar`, `/tax-schedules` |
| SC-09 리포트·브리핑 | Briefing API, `GET /businesses/{businessId}/monthly-reports/{month}`, **ProductSales API(P1-22 — SC-09-3)** |
| SC-10 대출·지원 제도 | Loan API, Credit API, Support Program API, `GET /document-guides` |
| SC-11 요식업 운영 | P2 이관(P2-14·P2-15) — 발주·재고·레시피 API는 P2 착수 시 재정의 |

---

## 5. API 목록

외부 클라이언트용 API 목록이다. 내부 수집·AI API는 20절에서 별도로 정의한다.

| 구분 | Method | Endpoint | 설명 |
| --- | --- | --- | --- |
| Auth | POST | `/auth/signup` | 이메일 회원가입 |
| Auth | POST | `/auth/login` | 로그인 |
| Auth | POST | `/auth/refresh` | Access Token 갱신 |
| Auth | POST | `/auth/logout` | 로그아웃 |
| User | GET | `/users/me` | 내 프로필 조회 |
| User | PATCH | `/users/me` | 내 프로필 수정 |
| Business | GET | `/businesses` | 내 사업장 목록 |
| Business | POST | `/businesses` | 사업장 등록 |
| Business | GET | `/businesses/{businessId}` | 사업장 상세 |
| Business | PATCH | `/businesses/{businessId}` | 사업장 수정 |
| Account | GET | `/businesses/{businessId}/accounts` | 계좌 목록 |
| Account | POST | `/businesses/{businessId}/accounts` | 계좌 연결 (1원 인증 발송) |
| Account | POST | `/accounts/{accountId}/verify` | 1원 인증코드 검증 |
| Account | POST | `/accounts/{accountId}/verification` | 1원 인증 재발송 |
| Account | PATCH | `/accounts/{accountId}/planned-execution` | 예정 거래 자동 반영 on/off (9.6 · 시연용) |
| Account | DELETE | `/accounts/{accountId}` | 계좌 연동 해제 |
| Account | POST | `/accounts/{accountId}/sync` | 금융망 거래 동기화 (202) |
| Account | POST | `/accounts/{accountId}/transactions/import` | CSV 거래 업로드 (202) |
| Transaction | GET | `/businesses/{businessId}/transactions` | 거래 목록·필터 |
| Transaction | GET | `/transactions/{transactionId}` | 거래 상세 |
| Transaction | PATCH | `/transactions/{transactionId}` | 분류·메모 수정 (사용자 확정) |
| Transaction | POST | `/transactions/{transactionId}/confirm-classification` | AI 제안 분류 수락 |
| Category | GET | `/transaction-categories` | 거래 카테고리 목록 (v1.4) |
| Transaction | POST | `/businesses/{businessId}/tax-review/bulk` | 종합소득세 검토 상태 일괄 변경 |
| Classification | POST | `/businesses/{businessId}/classification-jobs` | LLM 분류 작업 실행 (202) |
| Classification | GET | `/businesses/{businessId}/classification-jobs/current` | 진행 중인 분류 작업 조회 (8.5.1) — 없으면 `{running:false}` |
| Merchant | GET | `/businesses/{businessId}/merchants` | 거래처 사전 목록 |
| Merchant | PATCH | `/merchants/{merchantId}` | 거래처 사전 수정 |
| Pattern | GET | `/businesses/{businessId}/recurring-patterns` | 반복 거래 패턴 목록 |
| Pattern | GET | `/recurring-patterns/{patternId}` | 패턴 상세 (다음 예정 포함) |
| Pattern | POST | `/recurring-patterns/{patternId}/confirm` | 후보 승인 |
| Pattern | POST | `/recurring-patterns/{patternId}/dismiss` | 후보 무시 |
| Pattern | PATCH | `/recurring-patterns/{patternId}` | 패턴 수정·일시중지 |
| Planned | GET | `/businesses/{businessId}/planned-transactions` | 예정 거래 목록 |
| Planned | POST | `/businesses/{businessId}/planned-transactions` | 일회성 미래 거래 등록 |
| Planned | PATCH | `/planned-transactions/{plannedTransactionId}` | 예정 거래 수정 |
| Planned | DELETE | `/planned-transactions/{plannedTransactionId}` | 예정 거래 삭제 |
| Planned | POST | `/planned-transactions/{plannedTransactionId}/match` | 실제 거래 수동 매칭 |
| Planned | DELETE | `/planned-transactions/{plannedTransactionId}/match` | 매칭 해제 |
| Forecast | GET | `/businesses/{businessId}/forecast/balance` | 날짜별 예상 잔액 곡선 (원장 + 잔여 흐름 모델) |
| Forecast | GET | `/businesses/{businessId}/forecast/cash-gaps` | 정산 공백 목록 |
| Forecast | GET | `/businesses/{businessId}/forecast/accuracy` | 예측 정확도 리포트 (베이스라인 vs 모델 비교) |
| Forecast | GET | `/businesses/{businessId}/forecast/seasonality` | 계절성 전망 |
| Forecast | GET | `/businesses/{businessId}/forecast-settings` | 예측 옵션 조회 |
| Forecast | PATCH | `/businesses/{businessId}/forecast-settings` | 예측 옵션 수정 (`applyResidualModel` 포함) |
| Alert | GET | `/businesses/{businessId}/alerts` | 인앱 알림 목록 |
| Alert | POST | `/alerts/{alertId}/ack` | 알림 확인 처리 |
| Bank | GET | `/bank-codes` | 은행코드 목록 (공용) |
| Scenario | POST | `/businesses/{businessId}/scenarios` | 시나리오 생성·계산 |
| Scenario | GET | `/businesses/{businessId}/scenarios` | 저장 시나리오 목록 |
| Scenario | GET | `/scenarios/{scenarioId}` | 시나리오 결과 상세 |
| Scenario | PATCH | `/scenarios/{scenarioId}` | 시나리오 이름 수정 |
| Scenario | DELETE | `/scenarios/{scenarioId}` | 시나리오 삭제 |
| SafetyLine | GET | `/businesses/{businessId}/safety-line` | 안전선 조회 |
| SafetyLine | PUT | `/businesses/{businessId}/safety-line` | 안전선 설정 |
| SafetyLine | GET | `/businesses/{businessId}/safety-line/recommendations` | 안전선 추천값 |
| Reserve | GET | `/businesses/{businessId}/reserves` | 준비금 목록 |
| Reserve | POST | `/businesses/{businessId}/reserves` | 준비금 생성 |
| Reserve | PATCH | `/reserves/{reserveId}` | 준비금 수정 |
| Reserve | DELETE | `/reserves/{reserveId}` | 준비금 삭제 |
| Reserve | POST | `/reserves/{reserveId}/deposits` | 적립 기록 추가 |
| Reserve | GET | `/businesses/{businessId}/reserves/daily-plan` | 오늘 모아둘 돈 계산 |
| VAT | GET | `/businesses/{businessId}/vat/status` | 부가세 예수금·준비 현황 |
| Evidence | POST | `/businesses/{businessId}/evidences` | 증빙 업로드 (202, OCR 등록) |
| Evidence | GET | `/businesses/{businessId}/evidences` | 증빙 목록 |
| Evidence | GET | `/evidences/{evidenceId}` | 증빙 상세 (추출 결과 포함) |
| Evidence | PATCH | `/evidences/{evidenceId}` | 증빙 메타 수정 |
| Evidence | PATCH | `/evidences/{evidenceId}/extraction` | 추출 결과 수정·확정 |
| Evidence | GET | `/evidences/{evidenceId}/match-candidates` | 거래 매칭 후보 |
| Evidence | POST | `/evidences/{evidenceId}/link` | 거래 연결 |
| Evidence | DELETE | `/evidences/{evidenceId}/link` | 거래 연결 해제 |
| Evidence | DELETE | `/evidences/{evidenceId}` | 증빙 삭제 |
| Item | GET | `/businesses/{businessId}/items` | 품목 마스터 목록 |
| Item | PATCH | `/items/{itemId}` | 품목 수정·별칭 병합 |
| Item | GET | `/items/{itemId}/purchases` | 품목 구매 이력·단가 추이 |
| Item | GET | `/businesses/{businessId}/item-purchases` | 기간별 품목 지출 집계 |
| Settlement | GET | `/businesses/{businessId}/settlements` | 정산 대사 목록 |
| Settlement | POST | `/businesses/{businessId}/settlements` | 정산 대사 등록·계산 |
| Settlement | GET | `/settlements/{settlementId}` | 정산 대사 상세 |
| Settlement | PATCH | `/settlements/{settlementId}` | 정산 대사 수정 |
| Settlement | DELETE | `/settlements/{settlementId}` | 정산 대사 삭제 |
| Export | POST | `/businesses/{businessId}/exports` | 신고 준비자료 생성 (202) |
| Export | GET | `/businesses/{businessId}/exports` | 내보내기 목록 |
| Export | GET | `/exports/{exportId}` | 내보내기 상태·요약 |
| Export | GET | `/exports/{exportId}/download` | 파일 다운로드 |
| TaxDraft | GET | `/tax-filing-forms` | 신고서 서식 템플릿 목록 (공용, 14.3) |
| TaxDraft | POST | `/businesses/{businessId}/tax-drafts` | 신고서 초안 생성 (202) |
| TaxDraft | GET | `/businesses/{businessId}/tax-drafts` | 신고서 초안 목록 |
| TaxDraft | GET | `/tax-drafts/{draftId}` | 초안 상세 (항목 값·출처·근거 요약) |
| TaxDraft | PATCH | `/tax-drafts/{draftId}` | 항목 수정·홈택스 참고값 입력·확정 |
| TaxDraft | GET | `/tax-drafts/{draftId}/basis` | 항목별 근거 거래·증빙 목록 |
| TaxDraft | POST | `/tax-drafts/{draftId}/export` | PDF·홈택스 입력용 시트 즉시 다운로드 (민감값 미저장) |
| ProductSales | POST | `/businesses/{businessId}/product-sales` | **기간별** 상품 매출 등록·갱신 (upsert) |
| ProductSales | GET | `/businesses/{businessId}/product-sales` | 상품 구성비·순위·증감·변동 기여도 |
| ProductSales | GET | `/businesses/{businessId}/product-sales/{productKey}` | 상품 하나의 **기간별** 추이 |
| ProductSales | PATCH | `/businesses/{businessId}/product-sales/rename` | 상품명 일괄 변경·병합 |
| ProductSales | POST | `/businesses/{businessId}/product-sales/ocr` | 인쇄물 사진 여러 장에서 기간·상품 행·합계 행 추출 (비동기, 저장 안 함) |
| Calendar | GET | `/businesses/{businessId}/calendar` | 월간 캘린더 데이터 |
| Calendar | GET | `/businesses/{businessId}/calendar/{date}` | 일자 상세 |
| Calendar | GET | `/businesses/{businessId}/tax-schedules` | 세금 일정 목록 |
| Briefing | GET | `/businesses/{businessId}/briefings` | AI 브리핑 목록 |
| Briefing | GET | `/briefings/{briefingId}` | AI 브리핑 상세 |
| Report | GET | `/businesses/{businessId}/monthly-reports/{month}` | 월별 지출·수익 리포트 |
| Dashboard | GET | `/businesses/{businessId}/dashboard` | 매장용 오늘 요약 |
| Dashboard | GET | `/users/me/dashboard` | 다사업장 통합 대시보드 |
| Loan | GET | `/businesses/{businessId}/loans` | 대출 목록 |
| Loan | POST | `/businesses/{businessId}/loans` | 대출 수동 등록 |
| Loan | POST | `/businesses/{businessId}/loans/sync` | 금융망 대출 동기화 (202) |
| Loan | PATCH | `/loans/{loanId}` | 대출 수정 |
| Loan | DELETE | `/loans/{loanId}` | 대출 삭제 |
| Credit | GET | `/businesses/{businessId}/credit` | 신용등급 현황·예측 |
| Credit | GET | `/businesses/{businessId}/loan-products` | 대출 상품 추천 |
| Support | GET | `/support-programs` | 지원 제도 검색 (자연어 포함) |
| Support | GET | `/support-programs/{programId}` | 지원 제도 상세 |
| Support | GET | `/businesses/{businessId}/support-programs/recommendations` | 맞춤·공백 연계 추천 |
| Support | GET | `/document-guides` | 서류 발급처 사전 (P1-15) |
| Transfer | POST | `/users/me/transfer-simulations` | 매장 간 자금 융통 시뮬레이션 |
| Job | GET | `/jobs/{jobId}` | 비동기 작업 상태 조회 |

---

## 부록 A. 변경 이력

| 버전 | 날짜 | 변경 내용 |
| --- | --- | --- |
| v1.0 | 2026-08-31 | P0 코어 12개 + P1 확장 21개 기능 기준 최초 작성 |
| v1.1 | 2026-08-31 | SSAFY 교육용 금융망 인터페이스 예제 반영: 1원 인증 계좌 연결, 거래 동기화 중복 키(`finTransactionId`), 신용등급 A~E 체계·등급 구간, 대출 상품·상환 모델 현실화, 마이너스통장 통합, 은행코드 API 추가 |
| v1.2 | 2026-09-01 | 우선순위 조정 반영(SRS v1.2): What-if 시뮬레이션 고도화 P1-10 → P0-13 승격(시나리오 파라미터 재배치, `MENU_PRICE_CHANGE`는 P2 보류), 발주 보조 P1-20·P1-21 → P2-14·P2-15 이관(18장 API·메뉴/재고/발주 엔드포인트·관련 오류 코드 범위 제외) |
| v1.2.1 | 2026-09-01 | 개발 참조 효율화를 위해 `docs/api/` 도메인별 파일로 분할 (장·절 번호 유지, 내용 변경 없음). 18장은 P2 이관 스텁 대신 결번 처리 |
| v1.3 | 2026-09-01 | **백엔드 판정 반영** — ERD와의 불일치 해소 및 미결 항목 5건 확정. ① `jobId`를 문자열(`job_01J9…`)에서 **정수**로 변경(ERD ID 타입 `BIGINT` 통일과 정합), ② 23장 확인 사항 중 금융망 거래고유번호 채번·동기화 기간 분할·OCR 모델 비교 수행 방식·AI 워커 타임아웃/CB 정책·**예측 캐시 전략(사전 계산 + 파생값 요청 시 계산)** 확정. 엔드포인트·요청/응답 구조 변경 없음. |
| v1.4 | 2026-09-01 | **구현 반영** — ① **7.4 CSV 중복 판정 키에 `방향` 추가**(같은 시각·같은 금액의 입금/출금이 하나로 뭉개져 유실되는 문제), ② `GET /transaction-categories` 신규(프론트 하드코딩 방지), ③ 2.1 Refresh 쿠키 속성(`Path`·`SameSite`) 명시, ④ 7.4 금액 파싱 관용 처리 명시. **현재 구현 상태는 [99_구현현황.md](./99_구현현황.md)** |
| v1.5 | 2026-09-01 | **P1-22 상품별 매출 지표 추가.** 14장에 4개 엔드포인트(등록·조회·추이·rename)와 OCR 보조를 정의하고, 20장에 내부 워커 API `/internal/v1/ai/product-sales-extraction`(동기)를 추가했다. **상품 마스터를 두지 않고** `product_key` 정규화 + 신규 상품 명시 확인으로 표기 흔들림을 통제한다. 원가·마진은 범위 밖(P2-03)이라 수익성 표현을 금지한다 |
| v1.6 | 2026-09-03 | **종합소득세 신고서 초안 API 복구·번호 재배치.** ① `fe93ab2`에서 유실된 14.3 Tax Draft API(서식 템플릿 조회, 초안 생성 Job `TAX_DRAFT_GENERATION`, 항목 값·출처 6분류·근거 조회, 민감값 미저장 즉시 다운로드, 카테고리→서식 항목 매핑표, 별지 제74호서식·부표 원문 대조분 포함)와 14.2 부속 패키지 격하, 17.5 결번(P1-16), `document-assist` 재정의를 되살렸다. ② 절 번호 충돌 해소 — **상품별 매출 14.3~14.6 → 14.4~14.7**, **`document-assist` 20.9 → 20.10**(20.9는 `product-sales-extraction` 유지). ③ P1-22 중복 해소 — 상품별 매출이 P1-22, 신고자료 검토·확정 화면이 **P1-23** |
| v1.7 | 2026-09-03 | **상품별 매출: 일 단위 절대액 → 기간 구성비. 매출 용어 3층 고정.** ① 공통규약 **1.5 신설** — 판매/정산/입금 3층 용어표와 "카드는 방향이 반대인 두 가지" 주의(`CARD_SETTLEMENT` 입금 vs 금융망 `CREDIT_CARD_*` 출금). ② **14.4~14.7 전면 개정** — `businessDate` 단일 영업일 → `periodStart`/`periodEnd`(월 권장), 선택 금액 필드 Optional화(감열지 열 생략 대응), 금액 검증은 네 값이 다 있을 때만, `billedAmount`·합계 행 체크섬·`bootstrap` 일괄 수락 추가, `share` 분모를 **합계 기준**으로 변경(POS 인쇄 점유율과 정합 — 실측 확인), 일자별 추이 → 기간별 추이, OCR을 **1차 경로**로 승격(다중 이미지·`captureType`·비동기 Job). ③ **11.1** — `PRODUCT_SALES_CHANGE` 파라미터 신설(구성비로 총매출 배율 환산), `SALES_CHANGE`와 `DEPOSIT_DECREASE`의 동작 차이 명시, `MENU_PRICE_CHANGE` 보류 문구 폐기(전제가 P1-22 신설로 무효). ④ **15.3** — `income.byProduct` 추가로 P1-19↔P1-22 연결 실현. ⑤ **14.3** — 판매 층을 매핑표에서 제외하는 이유 명시, `revenueCheck.productSalesTotal` 참고값 추가. ⑥ **12.4** — 부가세 예수금은 입금 10/110 한 방식만 유지(판매 층 `vatAmount` 미사용). ⑦ **20.9** — 동기 → 비동기, 추출 요구사항 4건·촬영 유형 2종 정의. ⑧ P1-22 화면 귀속 SC-11 → **SC-09** |
| v1.8 | 2026-09-04 | **예측 엔진 AI 모델 도입.** 근거는 `docs/예측엔진_설계노트.md` v2. ① **10.1** — `appliedOptions.applyResidualModel`·`modelVersion`(엔진+모델 아티팩트) 추가, `adjustments.type`에 **`MODEL_RESIDUAL`** 신설, `basis.residual` 블록(출처·표본 일수·분위수 합계) 추가. 재현성 문구를 "동일 입력 **+ 동일 `modelVersion`**"으로 개정하고, 모델 산출값을 `FORECAST_RUN.basis`에 고정 저장해 재조회 시 재추론하지 않음을 명시. ② **10.4** — `modelComparison` 신설. 비교 지표는 모델 손실이 아니라 **다운스트림 지표**(`gapF1`·`firstShortfallDateErrorDays`)이며, 모델이 베이스라인을 못 넘으면 `activeVariant`가 `BASELINE`이 된다. ③ **10.7** — `applyResidualModel` 토글. 끈 상태에서도 곡선·부족일이 정상 산출된다(인수 조건). ④ **20.11 신설** `/internal/v1/ai/forecast-residual`(동기) — 잔여 시계열 분위수 예측. ⑤ **20.12 신설** `/internal/v1/ai/settlement-delay`(동기, 조건부) — 지연일·금액 배율 분위수. ⑥ **20.1 공통 규약 확장** — 예측 모델 4개 항(모델 버전 기록·분위수 단조성 검증·실패 시 베이스라인 폴백·OCR과 동시 실행 금지), 요청 본문에 적요·거래처명·계좌번호 미포함 |
| v1.9 | 2026-09-07 | **AI 워커 구현 반영 (20장).** ① **20.2 Callback 본문을 공통 Envelope으로 확정** — 이전 판은 20.3 예시(Envelope 없음)와 20.9 예시(Envelope)가 서로 달랐다. `data`에 `status`(`COMPLETED`/`PARTIAL`)와 `modelName`·`modelVersion`·`promptVersion`·`pipeline`을 평면으로 싣고, 실패는 `success:false` + `error{code,message,detail}` → Job `FAILED`. 콜백 `X-Request-Id`는 `ai-job-{jobId}-result` 고정, 5xx 3회 재시도·4xx 즉시 포기, 접수 전 거절 규칙(`422 PIPELINE_UNAVAILABLE`·`400` 파일 없음) 명시. `pipeline`에 연동 검증용 `STUB` 값 추가. ② **20.4** — 결과에 `evidenceId`·검산용 `supplyAmount`·검산 결과 `validation`(합계·품목합·사업자번호 체크섬·날짜) 추가, `AUTO`는 OCR 공급자 선정 전까지 `VLM`으로 처리. 요청 스키마 변경 없음. **워커 구현 현황은 [99_구현현황.md](./99_구현현황.md) 7장** |
| v1.10 | 2026-09-07 | **증빙 추출(20.4) 실측 반영.** KORIE 한국 영수증 val 50장 측정에서 검산 실패 26/50의 원인이 스키마 결함(면세물품·할인이 갈 곳이 없음)으로 확인되어 ① 확장 필드 `taxableAmount`·`taxFreeAmount`·`discountAmount`·`items[].discountAmount` 추가, `totalAmount` = 최종 결제 금액(할인 전 총합계 아님)으로 정의, `supplierName` = 매장명 줄. ② 검산식을 `과세 + 부가세 + 면세 = 합계`(마트형) / `공급가 + 부가세 = 합계`(세금계산서형)로 교체, 품목 합은 할인 반영. ③ `validation.needsReview`·`reviewReasons[]` 추가 — 신뢰도 임계값이 아니라 결정적 검사에서 나온다. 요청 스키마 변경 없음. 실측치는 99_구현현황 7장 |
| v1.11 | 2026-09-08 | **품목 정규화(20.5) 3차 LLM 구현 반영.** 유사도 0.70~0.92 저신뢰 건만 묶어 한 번 호출(effort low, 8초 제한, 실패·초과 시 미매칭 폴백), 후보에 없는 `itemId`·낮은 confidence 는 버림. 매핑에 `matchedBy`(`RULE`/`SIMILARITY`/`LLM`/null) 추가 — 기술스택 §6.5 "규칙 90% 처리" 비율 측정용. `suggestedCategory` 값 집합(`MATERIALS`/`SUPPLIES`/`EQUIPMENT`/`SERVICE`/`OTHER`) 명시. 요청 스키마 변경 없음 |
| v1.12 | 2026-09-08 | **모델 티어링 명문화(20.4).** 워커가 비용 절감을 위해 싼 모델로 먼저 추출하고 검산이 `needsReview`를 내는 건만 강한 모델로 재추출할 수 있음을 계약에 적었다 — `modelName`은 실제로 답한 모델, 승격 시 `warnings`에 한 줄. 백엔드 동작·스키마 변경 없음. 실측 승격률 12%(Sonnet 45%·Haiku 75%라 1차 모델 선정 기준이 된다) |
| v1.13 | 2026-09-08 | **`needsReview` 정의 확정(20.4) — 결정적 검사만, 모델 자기 신뢰도는 제외.** KORIE test 359장 재채점 근거: 신뢰도 임계값 0.7은 검토율을 13.9% → 23.4%로 늘리면서 정밀도는 18% → 23%에 머문다(데이터 자체 오류율 16.7% = 무작위 정밀도). 반면 회계 영향 오류 6건은 결정적 검사가 0/6, 신뢰도가 2/6(0.7)~5/6(0.85)을 잡는다. 그래서 `needsReview`는 "인쇄된 숫자가 서로 모순된다"는 불리언 게이트로, `fieldConfidence`는 검토 화면의 **필드 강조** 신호로 역할을 나눴다. `LOW_CONFIDENCE`는 열거값으로 남되 기본 미발생. 스키마 변경 없음 |
| v1.14 | 2026-09-08 | **부가세 세율 검산 추가(20.4)** — `validation.vatRatioConsistent` · 사유 `VAT_RATIO_INCONSISTENT`. 합성 증빙 45장 실측에서 모델이 과세·면세·부가세의 자리를 한 칸씩 밀어 적었는데 `과세 + 부가세 + 면세 = 합계`가 통과하는 것을 확인했다 — 덧셈은 교환법칙이 성립해 순열을 볼 수 없다. `부가세 = 과세분 × 10%`는 순열에 깨지므로 이 계열을 잡는다. 세액 0(영세율·면세)은 판정 제외. 밀림 2/2 포착, val 48건 오탐 0, test 359장 검토 물량 변화 없음(13.9% → 13.9%). 요청 스키마 변경 없음 |
| v1.15 | 2026-09-08 | **품목 행 정렬 검사 추가(20.4)** — `validation.itemsAligned` · 사유 `ITEM_ROWS_MISALIGNED`. 이름 열이 한 칸 밀려도 금액은 온전해 `Σ품목 = 합계`와 금액 검산이 전부 통과하는 것을 합성 현금영수증에서 확인했다(표 머리글이 첫 행 이름이 되고 맨 끝 상품이 숫자를 못 받는다). 산술로 볼 수 없어 구조 신호로 잡는다 — 품목 이름이 표 머리글 낱말, 또는 마지막 행만 금액 없음. 실측 오탐 0건/409장(머리글 신호), 게이트 13.9% → 14.2%. 요청 스키마 변경 없음 |
| v1.16 | 2026-09-08 | **`VLM` 경로의 `boundingBox` 미지원을 실측 근거로 명시(20.4)** — 값과 좌표를 함께 요구해 15장에서 재보니 값 정확도 99%인데 박스는 중심 적중 33%·IoU≥0.5 9%, 표 안의 작은 값은 13~27%였다. 박스를 100% 내며 `null` 을 안 내 자신 없는 건만 버릴 수도 없다. 스키마에는 유지(OCR 공급자가 붙으면 채워진다). 13.2 의 P1-02 완료 기준(좌표 하이라이트)을 "필드 강조"로 바꾸는 제안은 98_AI_백엔드_합의 ⑥ |
| v1.17 | 2026-09-08 | **오탐 처리 2건 추가(20.4).** ① **재추출** — 요청에 `extractionMode`(`STANDARD`/`THOROUGH`). 사용자가 결과가 틀렸다고 하면 백엔드가 같은 증빙으로 Job 을 다시 넣고 `THOROUGH` 를 준다. 워커는 타일링을 켜고 강한 모델로 바로 간다(타일링 실측: 오독 3건 중 2건 수정, 열화 품목명 exact 76.3→83.3%, 비용 +52% — 전건이 아니라 지목된 건에만 쓰므로 부담 없음). 실패는 1차로 폴백하지 않고 `FAILED`. 저장은 `is_current` 부분 UK 로 이미 가능해 마이그레이션 불필요. ② **`POST /internal/v1/ai/evidence-validation`(동기)** — 검산만 다시 돌린다. 모델 호출 없음(비용 0·즉시). 사용자가 값을 고친 뒤 검산 해소를 즉시 보여주고, 검산 규칙이 화면·API 서버에 복제되어 드리프트하는 것을 막는다 |
| v1.17 | 2026-09-09 | **계좌 재연결 규칙 (7.2).** 해제·만료된 계좌를 다시 등록할 수 없던 것을 고쳤다 — 계좌번호 해시가 전역 유일이라 `DISCONNECTED`·`VERIFICATION_EXPIRED` 행이 남아 있으면 같은 계좌를 다시 등록할 때 `409 ACCOUNT_ALREADY_LINKED` 로 막혔고, 화면에 재연결 경로가 없어 복구가 불가능했다. ① **7.2** — 이미 등록된 계좌번호의 재등록을 **세 갈래로 정의**: 같은 사업장의 해제·만료 행은 그 행을 **되살리고**(`201` + `PENDING_VERIFICATION`, 1원 재발송), 살아 있는 연결과 같은 사용자의 다른 사업장은 `409 ACCOUNT_ALREADY_LINKED`, **다른 사용자의 행은 `400 ACCOUNT_NO_INVALID`**(계좌 존재 여부를 흘리지 않기 위해 타인 계좌 규칙에 맞춤). ② 되살리기는 `accountId` 를 유지해 거래 이력을 잇고, 요청의 `bankCode`·`alias`·`isPrimary` 가 기존 값을 **덮어쓴다**. `lastSyncedAt`·`syncStatus` 는 유지, `balance` 는 `null` 로 되돌린다. ③ `PENDING_VERIFICATION` 재등록은 되살리기가 아니라 `409` — 재발송 상한이 `/verification` 에만 있어 이쪽으로 열면 금융망을 상한 없이 두드릴 수 있다. ④ **재발송은 인증 대기·만료 계좌만** 받는다: 해제된 계좌는 **`409 ACCOUNT_STATE_CONFLICT`**(22장 신규)이며 되살리는 문을 하나로 둔다. ⑤ `DELETE /accounts/{accountId}` 설명에 되돌릴 수 있음을 명시 |
| v1.18 | 2026-09-09 | **매출 인쇄물(20.9)에 검산 추가** — 결과에 `validation` 동봉. 20.9 에는 검산이 하나도 없어 숫자 오독이 그대로 통과했다. 행 안의 산술 4식(합성 90표본 1,059 라벨 행에서 전부 100% 성립)과 합계 행 체크섬(요구 2). 실측: 라벨 오탐 0, 검토 대상 2.2%, 정밀도 2/2. `Σ품목 = 합계 행`은 라벨 22%가 위반해 제외했고, 기간 누락(요구 1의 정상 동작)도 사유에서 제외했다. 요청 스키마 변경 없음 |
| v1.19 | 2026-09-09 | **판정 보수성 `judgmentAlpha` 를 계약에 넣었다 (20.11 · 10.1 · 10.4).** 워커는 `q10/q50/q90` 만 주고 곡선 판정은 API 서버가 하는데, **어느 분위수로 판정할지가 계약에 없었다** — 적어 두지 않으면 자연히 `q50`(α=0)을 쓴다. 지금 값이 마침 0.00 이라 사고가 안 났을 뿐이고, 「공백 경보니까 안전하게」라며 하한 쪽으로 올리는 선택이 훨씬 자연스러운데 **그러면 모델 채택 근거가 사라진다.** 짝지은 부트스트랩 실측(창 9,600개 · 데이터 `a82bc981`) — 운영 구간(과거 30일~1년)에서 모델 − 통계가 α=0.00 `+0.0162 [+0.0080, +0.0244]` 유의(게이트 통과) / α=0.05 `+0.0055 [−0.0027, +0.0131]` 유의차 없음 / α=0.10 `−0.0096` 유의하게 나쁨. α 를 올리는 것은 정밀도를 팔아 경보를 앞당기는 거래다(0.00→0.05 에서 최초 부족일 오차 9.14 → 7.63일, 정밀도 0.708 → 0.637 — 양쪽 유의). ① **20.11** — 응답에 `judgmentAlpha` 추가, 대표값 공식과 게이트 의존 근거표 명시. 값은 문서가 아니라 **응답에 싣는다**(재학습하면 α 가 가중치와 어긋난다). ② **10.1** — `basis.residual.judgmentAlpha` 추가. α 없이는 곡선을 재현할 수 없어 재현성 보증에 필요하고, **통계 폴백에도 같은 α 를 적용**해야 채택 비교가 성립한다. ③ **10.4** — `modelComparison` 의 채택 판정에 α=0.00 전제를 명시. ④ 표본 하한과의 결합도 적었다 — 과거 7~30일 구간은 α=0.05 여야 게이트를 넘으므로 `MIN_HISTORY` 를 내리면 α 도 다시 정해야 한다 |
| v1.20 | 2026-09-10 | **잔여 생성 주체와 그 전제를 계약에 못박았다 (10.1 · 10.4).** ① **잔여는 API 서버가 만든다** — `실제 합계 − 예정 거래 매칭분`. 집계 규칙 4가지(제외 거래는 실제 합계에서 아예 뺀다 · 매칭 거래를 뺀다 · **거래 없는 날도 0 으로 채운다** · `BigDecimal`)와 참조 구현(`ai/app/forecast/residual.py`)은 설계노트 M-1. ② **⚠ 매칭이 돌기 전에는 모델을 태우면 안 된다** — 뺄셈 대상인 `PLANNED_TRANSACTION.matched_transaction_id` 를 채우는 자동 매칭이 **9.5(P1-05)이고 미구현**이다(2026-09-10 확인: 컬럼·엔티티 필드만 있고 쓰는 코드 없음). 매칭이 비면 잔여가 「실제 전부」가 되어 곡선이 `시작잔액 + Σ예정 + Σ실제` 로 **예정 거래를 두 번 센다**. 「뺄셈이라 이중 계상이 구조적으로 불가능」은 매칭이 돌 때만 참이다. **P0-07 이 P1 기능에 의존하는 우선순위 역전**이므로 최소 매처를 P0 로 올리거나, 그때까지 `applied:false`·`source:NONE` 으로 둔다. ③ **10.4 `activeVariant` 는 실행마다 컨텍스트 길이로 정한다** — 30일 미만 미적용(워커 `422`) · **30일~1년 `MODEL`**(게이트가 통과하는 유일한 구간 `+0.0162 [+0.0080, +0.0244]`) · 1년 이상 `BASELINE`. `FORECAST_RUN` 단위 값이라 새 필드가 필요 없다 |
| v1.21 | 2026-09-10 | **증빙 결과에 `evidenceType` 추가** — 백엔드가 콜백에서 읽는데 워커가 보내지 않아 항상 `null` 이었다(13.1 업로드에서 종류는 선택이고, 안 고르면 `OTHER` 로 남는다). 모델이 인쇄된 제목을 보고 고르고, 제목이 안 보이면 `OTHER`. ⚠ 백엔드는 현재 종류가 `OTHER` 일 때만 적용해야 한다 — 지금은 무조건 덮어써서 사용자 선택이 지워진다. 정확도 미측정. 프롬프트 `evidence-extraction/v6` |
| v1.22 | 2026-09-10 | **`evidenceType` 을 되돌린다 — v1.21 은 품목 추출을 깎았다.** 종류 판별을 본문 추출과 같은 호출에 넣었더니 품목 행의 숫자가 이웃 행 것으로 밀렸다(합성 15장 A/B: 금액 86.4% → 69.0%, 수량 86.4% → 72.4%, 8행 악화·0행 개선). 프롬프트에 규칙을 더하면 밀리는 것을 두 번째로 관측했다(v5·v6). 프롬프트는 v4 로 복귀, 필드는 스키마에 남기고 값은 항상 `null`. 백엔드 가드는 그대로 둬도 된다 |
| v1.23 | 2026-09-11 | **P1-02 완료 기준을 「필드 강조」로 바꿨다 (13.2·13.3) + 매출 인쇄물 저장 정합 (14.4·14.7).** ① 13.2 의 완료 기준이던 **좌표 하이라이트를 뺐다** — `boundingBox` 는 계속 `null` 이고, 근거가 "좌표 품질이 나쁘다"에서 **"좌표를 채울 방법이 없다"** 로 바뀌었다(공급자 후보 Azure·CLOVA 둘 다 종결, 호출 0건·비용 0원). 대신 `validation.needsReview` 정렬 + `fieldConfidence` 강조 + `reviewReasons` 문장이 완료 기준이다. ⚠ **`fieldConfidence` 는 게이트가 아니다**(정밀도 23~25%, 기저 오류율 16.7%). ② 14.4 의 `grossAmount`·`quantity` 를 **선택으로 내렸다** — 58mm 감열지에 총매출 열이 인쇄되지 않는다(실측 90표본 중 30표본 전부·행 33.3%). 대신 `grossAmount`·`billedAmount` 중 **하나는 있어야 한다**(구성비 분모). ③ `checksumStatus = MISMATCHED` 가 **22.2% 나오는 정상값**임을 명시 — 빨간 경고로 그리면 사장님이 매달 경고를 본다. 구성비 분모는 합계 행이 아니라 **보이는 행의 합**이다. ④ 14.7 은 상품 행을 저장하지 않는 결정을 유지하되 **추출 기록**(기간·읽은 열·합계 행·검산·모델 메타)은 남긴다. `productKey` 정규화 주체는 **서버**다. ⑤ 13.2 목록에 `needsReview` 필터·정렬을 명시하고(`null` 은 `true`·`false` 어느 쪽도 아니다), 13.3 에 **수정 이력·검산 재실행·부분 수정(`null` = 안 보냄)** 을 못박았다 |
| v1.24 | 2026-09-11 | **뒤집힌 기간은 보내지 않는다(20.9).** `V8` 이 `CHECK (period_end >= period_start)` 를 걸어, 그대로 보내면 INSERT 거절로 추출 전체가 사라진다. 기간을 비우고 `warnings` 에 남긴다 — 기간 없음은 요구 1 의 정상 상태다. 합성 90표본 관측 0건이지만 오탐이 원리적으로 불가능하고(시작 > 종료는 순서다) 나면 잃는 것이 크다. 요청 스키마 변경 없음 |
| v1.25 | 2026-09-11 | **해제된 계좌를 돈 세는 자리에서 뺀다 (7.2 · 8.2).** 연동을 끊어도 거래·잔액이 DB 에 남아 **화면이 해제된 계좌의 잔액을 총합에 계속 더하고 거래 목록에도 그대로 보여줬다.** 기준이 이미 두 벌로 갈려 있었던 것이 원인이다 — 예측 엔진은 `CONNECTED` 만 세는데(`AccountBalancesService`) 프론트 합계와 거래 목록은 계좌 상태를 아예 보지 않았다. ① **7.2** — 해제 시 `balance`·`balanceUpdatedAt` 을 `null` 로 되돌린다(되살리기와 같은 이유: 시점 값이고, 해제 뒤에는 동기화가 돌지 않아 이 값만 낡는다). 마지막 잔액은 `balance_after` 에 남아 정보 손실은 없다. **보존은 「합계에 기여한다」는 뜻이 아니라는 것**을 명시했다. ② **8.2** — `includeDisconnected` 신설(기본 `false`). `accountId` 로 지목해도 이 값이 필요하다 — 문을 둘로 두면 어느 쪽이 합계에 들어가는지가 호출자마다 갈린다. 빠지는 것은 **해제뿐**이다(인증 대기·만료 계좌에는 CSV 자료가 있을 수 있다). ③ **예측 입력은 기준이 더 좁다** — 반복 패턴 탐지가 `CONNECTED` 만 읽도록 맞췄다. 갈려 있는 동안에는 시작 잔액에 없는 계좌의 반복 지출이 곡선을 깎아 **없는 정산 공백**을 만들 수 있었다. ④ **종합소득세 검토·신고서 초안도 기본값을 따른다**(팀 결정) — 과세기간 중 해제한 계좌의 거래가 초안 합계에서 빠지므로 P0-12 착수 시 재검토한다. ⑤ **해제를 예측 재계산 트리거에 넣었다**(공통규약 2.6) — 곡선은 `FORECAST_RUN` 에 값으로 굳어 있어 잔액 컬럼을 비우는 것만으로는 화면 총잔액이 줄지 않는다. 걸지 않으면 04:00 배치까지 옛 총잔액이 그대로 나온다 |
| v1.26 | 2026-09-15 | **앱은 Refresh Token 을 본문으로 주고받는다 (2.1 · 6.2).** 앱이 **30분마다 로그아웃**됐다 — Refresh Token 을 `Secure`·`HttpOnly` 쿠키로만 줬는데 React Native 에는 쿠키 저장소가 없어, Access Token 이 만료되면 갱신할 방법 자체가 없었다. ① **2.1** — 앱 본문 채널 신설. `POST /auth/login` 에 헤더 `X-Client-Type: app` 이 붙으면 본문에 `refreshToken`·`refreshExpiresIn` 을 싣고 `Set-Cookie` 를 내리지 않는다. `POST /auth/refresh`·`/auth/logout` 은 본문 `{refreshToken}` 을 받으며 **쿠키보다 본문을 먼저** 본다. 회전·수명(14일)·오류(`401 AUTH_TOKEN_INVALID`)는 쿠키 방식과 같다. ② **⚠ 갱신·로그아웃의 채널은 헤더가 아니라 「토큰이 온 곳」이 정한다** — `X-Client-Type` 만 보고 갈랐다면 웹에 XSS 가 생겼을 때 스크립트가 그 헤더를 붙여 쿠키로 갱신을 요청하고 **Refresh Token 을 본문으로 빼 갈 수 있다**. 그러면 `HttpOnly` 를 쓴 의미가 없다. 로그인만 헤더로 가른다(비밀번호를 아는 쪽에만 답하므로 탈취 경로가 아니다). ③ **6.2** — 앱 요청·응답 예시 추가. **웹은 변경 없다** — 헤더 없는 로그인과 쿠키 갱신의 응답 본문에는 `refreshToken` 키가 없다(`non_null`). 새 엔드포인트는 없다 |
| v1.27 | 2026-09-15 | **P1-06 원자재 가격 지표를 범위에서 뺀다(결번).** 시세를 붙이려면 KAMIS·ECOS 수집 배치, 전역 지표 테이블 2종, 사업장별 재료비 비중, 영향 계산 API 까지 한 줄로 이어져야 하는데 **어느 것도 시작되지 않았다.** ① **10.5 삭제** — `PUT`·`GET /businesses/{businessId}/ingredients`, `GET /market-indicators`, `GET /businesses/{businessId}/forecast/material-impact` 세 묶음이 모두 빠진다. 절 번호는 10.6·10.7 참조 때문에 결번으로 비워 둔다. ② **20.8 시세 적재 삭제** — `POST /internal/v1/market-prices:batch`. ③ **10.7 `applyMaterialIndex` 삭제** — DB 컬럼도 함께 지웠다(백엔드 `V20`). 보내도 무시되는 것이 아니라 **응답에 키 자체가 없다.** ④ 곡선 보정 마커 `MATERIAL_PRICE`(10.1) 삭제. ⑤ 공개 경로에서 `GET /market-indicators` 를 뺐다(2.1 · 권한표). ⑥ **11.1 `MATERIAL_COST_CHANGE` 는 남긴다** — 재료비 변화 What-if 는 P0-13 이고 시세 없이 사용자가 변화율을 직접 넣으면 성립한다. 선택 필드였던 `indicatorCode` 만 뺐다 |
| v1.28 | 2026-09-16 | **재료비 변화 What-if 를 뺀다 — 11.1 파라미터 9종 → 8종.** v1.27 이 ⑥에서 `MATERIAL_COST_CHANGE` 를 **남긴다**고 적었는데 **그 판단이 뒤집혔다.** 남은 `rate` 만으로는 **무엇에 곱하는지가 이 문서 어디에도 없다** — `SALES_CHANGE` 는 대상 패턴을 표로 못박아 뒀는데(「`CARD_SETTLEMENT`·`PLATFORM_SETTLEMENT`·`CASH_SALES`·`TRANSFER_SALES` 패턴만」) 이쪽은 표 한 줄이 전부였고, 유일한 근거이던 기술스택 §4.10 의 「가격 변동률 × 재료비 비중」이 P1-06 과 함께 나갔다. 새로 정의할 수는 있었다(예정 거래 중 분류가 `MATERIALS` 인 출금에 곱한다 — `PLANNED_TRANSACTION.category_id` 가 이미 있다). **그러나 커버리지가 반쪽이다** — 금액·날짜가 매번 다른 현금 식자재 구매는 반복 패턴이 서지 않아 잔여 흐름 축으로 가는데, 그 축은 `residualDeposit`/`residualWithdrawal` 두 숫자뿐이라 분류가 없어 재료비만 골라낼 수 없다. ① **11.1 표에서 행 삭제.** ② DB 제약도 함께(`V29` 로 `ck_sa_type` 재생성 — `V1` 은 체크섬 때문에 못 고친다). ③ **매출 시뮬레이션은 `PRODUCT_SALES_CHANGE` 가 맡는다** — 구성비라는 실측 근거가 있다. ④ **`MATERIALS` 거래 분류(8장)는 그대로다** — 분류·집계와 시뮬레이션은 다른 기능이다 |

> **`v1.17` 이 두 번 쓰였다** (20.4 오탐 처리 · 7.2 계좌 재연결). 서로 다른 브랜치에서 같은
> 번호를 동시에 집어 생겼고 둘 다 머지된 뒤라 그대로 둔다 — 장이 달라 본문의 `*(v1.17)*`
> 표시는 헷갈리지 않는다. **번호는 머지 직전에 이 표를 보고 집는다.**
