"""stdout 전용 로깅 + 민감 정보 마스킹 (기술스택 §8.7 규칙 8, 인프라 제약 §4.1 #7).

마스킹은 최후의 안전망이다. 규칙은 "계좌번호·주민번호·사업자번호·증빙 원본을 로그에
넣지 않는다"이고, 이 필터는 실수로 섞여 들어간 값을 패턴으로 가린다. 필터를 믿고 원문을 찍지 않는다.
"""

import logging
import re
import sys

from app.common.request_id import request_id_var

_PATTERNS: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"(?<!\d)\d{6}-[1-4]\d{6}(?!\d)"), "[주민번호]"),
    (re.compile(r"(?<!\d)\d{3}-\d{2}-\d{5}(?!\d)"), "[사업자번호]"),
    (re.compile(r"(?<!\d)\d{10,14}(?!\d)"), "[숫자열]"),  # 계좌번호 후보
]


class SensitiveDataFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        message = record.getMessage()
        for pattern, replacement in _PATTERNS:
            message = pattern.sub(replacement, message)
        record.msg = message
        record.args = ()
        return True


class RequestIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = request_id_var.get()
        return True


def configure_logging(level: str = "INFO") -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(
        logging.Formatter("%(asctime)s %(levelname)-5s [%(request_id)s] %(name)s - %(message)s")
    )
    handler.addFilter(RequestIdFilter())
    handler.addFilter(SensitiveDataFilter())
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(level)
