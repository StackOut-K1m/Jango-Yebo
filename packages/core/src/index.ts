// 웹(frontend)과 Android 앱(mobile)이 함께 쓰는 코드.
//
// 규칙: 이 패키지는 window·document·React Native API를 직접 참조하지 않는다.
// 플랫폼에 따라 달라지는 것(토큰 저장소 등)은 인터페이스만 정의하고 각 앱이 주입한다.

export { configureCore, createMemoryTokenStore, getConfig, resetCore } from './config';
export type { CoreConfig, TokenStore } from './config';

export { request } from './api/client';
export type { RequestOptions } from './api/client';

export { getMe, login, logout, signup, updateMe } from './api/auth';

export {
  createAccount,
  createBusiness,
  disconnectAccount,
  getBusiness,
  listAccounts,
  listBankCodes,
  listBusinesses,
  purgeAccount,
  resendVerification,
  syncAccount,
  updateBusiness,
  verifyAccount,
} from './api/business';

export { getJob, isJobTerminal } from './api/job';

export {
  confirmRecurringPattern,
  createPlannedTransaction,
  deletePlannedTransaction,
  detectRecurringPatterns,
  dismissRecurringPattern,
  getRecurringPattern,
  listPlannedTransactions,
  listRecurringPatterns,
  updatePlannedTransaction,
  updateRecurringPattern,
} from './api/planned';

export {
  getBalanceForecast,
  getCashGaps,
  getForecastSettings,
  patchForecastSettings,
  refreshForecast,
} from './api/forecast';
export type { ForecastDays } from './api/forecast';

export { getSafetyLine, putSafetyLine } from './api/safety';

export { createScenario, deleteScenario, getScenario, listScenarios, renameScenario } from './api/scenario';

export {
  deleteEvidence,
  getEvidence,
  getEvidenceFile,
  linkEvidence,
  listEvidences,
  listMatchCandidates,
  patchEvidence,
  patchEvidenceExtraction,
  reExtractEvidence,
  unlinkEvidence,
  uploadEvidence,
} from './api/evidence';

export { getCredit, listLoanProducts, listLoans, syncLoans } from './api/loan';

export {
  bulkUpdateTaxReview,
  createTaxDraft,
  exportTaxDraft,
  getTaxDraft,
  getTaxDraftBasis,
  listTaxDrafts,
  listTaxFilingForms,
  patchTaxDraft,
  deleteTaxDraft,
} from './api/taxDraft';
export { fileNameFrom } from './api/client';
export type { DownloadedFile } from './api/client';

export {
  getSupportProgram,
  getSupportRecommendations,
  listDocumentGuides,
  listSupportPrograms,
} from './api/support';

export {
  getProductSalesShares,
  getProductSalesTrend,
  registerProductSales,
  renameProduct,
  uploadProductSalesOcr,
} from './api/productSales';

export {
  ackAlert,
  createSettlement,
  deleteSettlement,
  getSettlement,
  listAlerts,
  listSettlements,
  updateSettlement,
} from './api/settlement';

export { createExport, downloadExport, getExport, listExports } from './api/exportPackage';

export {
  getTransaction,
  importTransactionsCsv,
  confirmClassification,
  getCurrentClassificationJob,
  requestClassification,
  listTransactionCategories,
  listTransactions,
  updateTransaction,
} from './api/transaction';

export { getEndpointState, isMockData, resolveRegistry } from './api/registry';
export type { EndpointKey, EndpointState } from './api/registry';

export { finCodeOf, finMessage, needsResend } from './api/finCodes';

export { isEnvelope, NOT_IMPLEMENTED_ERROR } from './api/envelope';
export type { ApiErrorBody, ApiResult, Envelope } from './api/envelope';

export * from './api/types/02_common';
export * from './api/types/06_auth';
export * from './api/types/07_business';
export * from './api/types/08_transaction';
export * from './api/types/09_planned';
export * from './api/types/10_forecast';
export * from './api/types/11_scenario';
export * from './api/types/12_safety';
export * from './api/types/13_evidence';
export * from './api/types/14_export';
export * from './api/types/14_productsales';
export * from './api/types/14_settlement';
export * from './api/types/14_taxdraft';
export * from './api/types/16_loan';
export * from './api/types/17_support';

export * from './domain/account';

export * from './domain/balanceHistory';

export * from './domain/risk';

export * from './format/money';

export * from './format/date';
