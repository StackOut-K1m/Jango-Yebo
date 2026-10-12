package com.jangoyebo.core.support;

import java.math.BigDecimal;

/** 서류 발급처 사전 응답 (17.4)과 공고 상세에 붙는 제출 서류 (17.2). */
public final class DocumentGuideDtos {

    private DocumentGuideDtos() {
    }

    /** 사전 한 줄 (17.4). */
    public record Guide(Long documentGuideId,
                        String documentName,
                        String issuer,
                        String issueUrl,
                        String method,
                        BigDecimal fee,
                        String estimatedTime,
                        String notes) {

        static Guide from(DocumentGuide guide) {
            return new Guide(
                    guide.getId(),
                    guide.getDocumentName(),
                    guide.getIssuer(),
                    guide.getIssueUrl(),
                    guide.getMethod(),
                    guide.getFee(),
                    guide.getEstimatedTime(),
                    guide.getNotes());
        }
    }

    /**
     * 공고가 요구하는 서류 하나 (17.2 {@code requiredDocuments[]}).
     *
     * <p><b>워커가 준 이름이 정본이다.</b> 공고에 적힌 표기를 그대로 보여 준다 — 사전의
     * 표제어로 바꿔 쓰면 사장님이 공고와 화면을 대조할 때 다른 서류로 읽는다.
     *
     * <p><b>{@code issueUrl}·{@code fee} 는 사전에서만 온다.</b> 20.6 워커가 이 둘을 일부러
     * 비워 보낸다 — 공고 본문에서 주워 온 URL 은 죽은 링크이거나 엉뚱한 기관 페이지다.
     *
     * <p>사전에 없는 서류는 <b>빼지 않고 링크만 비운다.</b> 목록에서 지우면 준비해야 할
     * 서류가 화면에서 조용히 사라지고, 그 사실을 사장님이 알 방법이 없다. 응답 규약이
     * {@code non_null} 이라 빈 칸은 키 자체가 나가지 않는다.
     */
    public record RequiredDocument(String documentName,
                                   String issuer,
                                   String issueUrl,
                                   String method,
                                   BigDecimal fee) {

        /** 사전에서 못 찾았을 때 — 워커가 준 것만 남긴다. */
        static RequiredDocument unmatched(String documentName, String issuer, String method) {
            return new RequiredDocument(documentName, issuer, null, method, null);
        }

        /** 사전과 이어졌을 때. 이름은 공고 표기를, 빈 칸은 사전 값을 쓴다. */
        static RequiredDocument matched(String documentName, String issuer, String method,
                                        DocumentGuide guide) {
            return new RequiredDocument(
                    documentName,
                    issuer != null && !issuer.isBlank() ? issuer : guide.getIssuer(),
                    guide.getIssueUrl(),
                    method != null && !method.isBlank() ? method : guide.getMethod(),
                    guide.getFee());
        }
    }
}
