package com.jangoyebo.core.tax;

import java.util.List;

/**
 * 내보낼 문서의 포맷 중립 모양 (API 14.3 {@code export}).
 *
 * <p><b>렌더러만 갈아 끼우려고 둔 이음매다.</b> 두 포맷이 공유하는 일이 대부분이고
 * — {@code READY} 가드 · 사업장 열 병합 · {@code overrides} 얹기 · 민감값 주입 ·
 * 항목 정렬 · 대조 행 · 감사 로그 — 다른 것은 그리는 방법뿐이다. 이 모델이 없으면
 * PDF 를 붙일 때 그 조립을 다시 짜야 한다.
 *
 * <p><b>값이 열 목록인 이유.</b> 별지 제74호서식은 <b>개인 단위</b>라 사업장이 여럿이면
 * 사업장별 열과 합계 열로 한 장에 담는다(서식에 칸이 4개 있다). 처음부터 열로 두지 않으면
 * 나중에 모델 모양이 통째로 바뀐다.
 *
 * @param columns 열 이름. 사업장 하나면 한 칸, 여럿이면 사업장들 + 「합계」
 */
record ExportDocument(String title,
                      String subtitle,
                      List<String> columns,
                      List<Section> sections,
                      String disclaimer) {

    record Section(String label, String note, List<Row> rows) {
    }

    /**
     * 한 줄.
     *
     * @param values      {@code columns} 와 길이가 같다. 숫자·글자·{@code null} 이 섞인다
     * @param manualEntry 값을 우리가 채우지 않고 <b>사람이 홈택스에서 직접 넣는</b> 줄.
     *                    주민등록번호·환급금 계좌·소득공제·세액공제·기납부세액이 그렇다 —
     *                    옮겨 적는 사람이 <b>어디서 멈추는지</b> 알아야 한다
     */
    record Row(String label,
               List<Object> values,
               String source,
               Integer basisCount,
               String note,
               boolean manualEntry) {

        static Row of(String label, List<Object> values, String source, Integer basisCount,
                      String note) {
            return new Row(label, values, source, basisCount, note, false);
        }

        /** 사람이 직접 넣는 줄. 값 칸은 비워 두고 안내만 남긴다. */
        static Row manual(String label, int columnCount, String note) {
            return new Row(label, java.util.Collections.nCopies(columnCount, null),
                    "직접 입력", null, note, true);
        }
    }
}
