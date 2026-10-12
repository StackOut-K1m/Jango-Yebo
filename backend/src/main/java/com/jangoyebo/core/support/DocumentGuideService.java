package com.jangoyebo.core.support;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.support.DocumentGuideDtos.Guide;
import com.jangoyebo.core.support.DocumentGuideDtos.RequiredDocument;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 서류 발급처 사전 (API 17.4, P1-15).
 *
 * <p>이 사전은 <b>17.2 의 빠진 반쪽이다.</b> 20.6 워커는 제출 서류의 이름·발급처·발급방법까지만
 * 담고 {@code issueUrl}·{@code fee} 는 일부러 비워 보낸다 — 공고 본문에서 주워 온 URL 을
 * 그대로 실으면 죽은 링크나 엉뚱한 기관 페이지가 사장님에게 나간다. 17.2 명세는 그 두 칸이
 * 채워진 응답을 요구하고, <b>채우는 자리가 여기다.</b>
 *
 * <p><b>발급 대리는 범위 밖이다</b>(17.4). 어디서 어떻게 떼는지만 알려 준다.
 */
@Service
public class DocumentGuideService {

    /**
     * 표기 흔들림을 걷어내는 문자들.
     *
     * <p>공고의 서류명은 자유 문장이라 「사업자등록증명」·「사업자 등록증명」·
     * 「사업자등록증명(최근 3개월)」이 다 온다. 공백·중점·괄호·쉼표·붙임표·빗금을 빼고
     * 소문자로 맞춘 것이 비교 단위다.
     *
     * <p><b>「사본」·「원」 같은 꼬리말은 규칙으로 떼지 않는다.</b> 자동으로 잘라내면
     * 「등기사항전부증명서」와 「등기사항전부증명원」처럼 실제로 다른 서류까지 한 칸으로
     * 접힐 수 있다. 대신 시드의 {@code match_keys} 에 별칭을 <b>손으로</b> 적었다 —
     * 어떤 표기를 같은 서류로 볼지는 사람이 정할 일이다.
     */
    private static final Pattern NOISE = Pattern.compile("[\\s()\\[\\]·,\\-/]+");

    private final DocumentGuideRepository guides;
    private final SupportProgramRepository programs;

    public DocumentGuideService(DocumentGuideRepository guides,
                                SupportProgramRepository programs) {
        this.guides = guides;
        this.programs = programs;
    }

    /**
     * 사전 조회 (17.4).
     *
     * <p>{@code programId} 가 있으면 <b>그 공고가 요구하는 서류만</b> 준다. {@code q} 가 있으면
     * 이름·발급처·별칭에서 찾는다. <b>둘 다 없으면 사전 전체를 준다</b> — 수십 행짜리 기준
     * 데이터이고, 공고와 무관하게 「무슨 서류를 어디서 떼나」를 보는 화면이 있다.
     *
     * <p>둘이 같이 오면 {@code programId} 가 이긴다. 공고에 딸린 목록을 키워드로 다시 거르는
     * 것은 화면이 할 일이고, 여기서 두 조건을 곱하면 <b>0건이 나왔을 때 무엇 때문인지</b>
     * 알 수 없다.
     */
    @Transactional(readOnly = true)
    public List<Guide> list(Long programId, String q) {
        if (programId != null) {
            return forProgram(programId);
        }
        if (q != null && !q.isBlank()) {
            String trimmed = q.trim();
            return guides.searchByKeyword(trimmed, normalize(trimmed)).stream()
                    .map(Guide::from)
                    .toList();
        }
        return guides.findByActiveTrueOrderByIdAsc().stream().map(Guide::from).toList();
    }

    /**
     * 공고 상세(17.2)에 붙일 제출 서류 목록.
     *
     * <p>저장된 값은 워커가 준 그대로다({@code eligibility.requiredDocuments}). 여기서 사전을
     * 붙여 발급 링크와 수수료를 채운다.
     *
     * <p><b>못 찾은 서류도 그대로 남긴다.</b> 목록에서 지우면 준비해야 할 서류가 화면에서
     * 조용히 사라진다 — 링크가 없는 것보다 나쁘다.
     */
    @Transactional(readOnly = true)
    public List<RequiredDocument> attachGuides(List<?> storedDocuments) {
        if (storedDocuments == null || storedDocuments.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Object stored : storedDocuments) {
            if (!(stored instanceof Map<?, ?> map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) map;
            String name = text(row.get("documentName"));
            if (name == null) {
                // 이름 없는 서류는 사장님에게 보여 줄 것이 없다. 링크도 붙일 수 없다.
                continue;
            }
            rows.add(row);
            keys.add(normalize(name));
        }
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<String, DocumentGuide> byKey = index(keys);

        List<RequiredDocument> documents = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String name = text(row.get("documentName"));
            String issuer = text(row.get("issuer"));
            String method = text(row.get("method"));
            DocumentGuide guide = byKey.get(normalize(name));
            documents.add(guide == null
                    ? RequiredDocument.unmatched(name, issuer, method)
                    : RequiredDocument.matched(name, issuer, method, guide));
        }
        return documents;
    }

    /**
     * 서류명을 비교 단위로 줄인다. <b>정규화 규칙은 여기 한 곳에만 있다</b> — 시드의
     * {@code match_keys} 도 이 규칙으로 적은 값이라, 규칙을 고치면 시드도 같이 고쳐야 한다.
     */
    static String normalize(String value) {
        return NOISE.matcher(value).replaceAll("").toLowerCase();
    }

    /** 공고가 요구하는 서류만. 구조화되지 않은 공고는 17.2 와 같이 404 다. */
    private List<Guide> forProgram(Long programId) {
        SupportProgram program = programs.findById(programId)
                .filter(SupportProgram::isStructured)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROGRAM_NOT_FOUND));

        Object stored = program.getEligibility().get("requiredDocuments");
        if (!(stored instanceof List<?> list) || list.isEmpty()) {
            // 공고가 서류를 안 적었거나 구조화가 못 읽은 것이다. 둘 다 사전이 메울 수 없다.
            return List.of();
        }

        Set<String> keys = new LinkedHashSet<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                String name = text(map.get("documentName"));
                if (name != null) {
                    keys.add(normalize(name));
                }
            }
        }
        if (keys.isEmpty()) {
            return List.of();
        }
        // 서류 하나가 별칭 여러 개로 잡힐 수 있어 행 기준으로 중복을 걷는다.
        Map<Long, DocumentGuide> distinct = new LinkedHashMap<>();
        for (DocumentGuide guide : index(keys).values()) {
            distinct.putIfAbsent(guide.getId(), guide);
        }
        return distinct.values().stream().map(Guide::from).toList();
    }

    /**
     * 정규화된 이름 → 사전 행.
     *
     * <p>짝은 <b>질의가 알려 준다</b>({@code match_key}). 여기서 표제어와 다시 비교하면
     * 별칭으로 걸린 것을 전부 버리게 된다 — 「사업자등록증」이 「사업자등록증명」에 걸리는
     * 것이 이 표의 존재 이유다.
     */
    private Map<String, DocumentGuide> index(Set<String> keys) {
        List<DocumentGuideRepository.GuideMatch> matches = guides.findMatches(keys);
        if (matches.isEmpty()) {
            return Map.of();
        }

        Map<Long, DocumentGuide> byId = new LinkedHashMap<>();
        for (DocumentGuide guide : guides.findAllById(
                matches.stream().map(DocumentGuideRepository.GuideMatch::getGuideId).toList())) {
            byId.put(guide.getId(), guide);
        }

        Map<String, DocumentGuide> byKey = new LinkedHashMap<>();
        for (DocumentGuideRepository.GuideMatch match : matches) {
            DocumentGuide guide = byId.get(match.getGuideId());
            if (guide != null) {
                byKey.putIfAbsent(match.getMatchKey(), guide);
            }
        }
        return byKey;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
