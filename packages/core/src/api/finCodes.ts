import type { ApiErrorBody } from './envelope';

/**
 * 금융망 오류 코드 → 사용자 문구.
 *
 * 502 FIN_API_FAILED 의 message 는 "1원 인증 검증에 실패했습니다" 처럼 뭉뚱그려져 있어
 * 사용자가 무엇을 해야 할지 알 수 없다. detail.finCode 가 실제 이유를 담고 있다
 * (공통규약 2.4). 출처는 SSAFY 교육용 금융망 API 워크북의 ERROR_01 시트다.
 *
 * 여기 없는 코드는 null 을 돌려주고, 화면이 서버 message 를 그대로 쓴다.
 */
const MESSAGE: Record<string, string> = {
  // 계좌 인증
  A1086: '1원을 먼저 받아 주세요.',
  A1087: '인증 시간이 지났습니다. 1원을 다시 받아 주세요.',
  A1090: '코드가 맞지 않습니다. 통장을 다시 확인해 주세요.',

  // 계좌
  A1003: '계좌번호를 다시 확인해 주세요.',

  // 우리 설정 문제다. 사용자가 할 수 있는 일이 없으므로 그렇게 알린다.
  H1008: '은행 연결 설정에 문제가 있습니다. 잠시 후 다시 시도해 주세요.',
  H1009: '은행 연결 정보가 올바르지 않습니다. 잠시 후 다시 시도해 주세요.',
  H1003: '잠시 후 다시 시도해 주세요.',
};

/** 재발송으로 풀리는 상황인가. 화면이 재발송 버튼을 강조할 때 쓴다. */
export function needsResend(error: ApiErrorBody): boolean {
  const code = finCodeOf(error);
  return code === 'A1086' || code === 'A1087';
}

export function finCodeOf(error: ApiErrorBody): string | null {
  const detail = error.detail;
  if (typeof detail !== 'object' || detail === null) return null;
  const code = (detail as { finCode?: unknown }).finCode;
  return typeof code === 'string' ? code : null;
}

/** 금융망 코드에 대응하는 사용자 문구. 모르는 코드면 null. */
export function finMessage(error: ApiErrorBody): string | null {
  const code = finCodeOf(error);
  return code ? (MESSAGE[code] ?? null) : null;
}
