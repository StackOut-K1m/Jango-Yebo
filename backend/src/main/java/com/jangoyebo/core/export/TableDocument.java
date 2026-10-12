package com.jangoyebo.core.export;

import java.util.List;

/**
 * 준비자료 시트 한 장의 포맷 중립 모양 (API 14.2).
 *
 * <p><b>14.3 의 {@code ExportDocument} 와 모양이 다르다.</b> 저쪽은 서식 항목을 세로로 세운
 * 「항목 / 값 / 출처 / 근거 건수」 한 장이고, 이쪽은 거래 1,200 행이 흐르는 <b>가로형 레코드
 * 표</b>다. 같은 렌더러로 그리려다 열 구성을 조건문으로 가르기 시작하면 두 산출물이 서로를
 * 망가뜨린다.
 *
 * @param fileName ZIP 안에서 쓸 이름. 기획정리본 §6.10 의 번호 접두사를 그대로 쓴다
 * @param note     시트 맨 아래 고지. <b>모든 시트에 기준일·대상 기간·개인정보 고지가 들어간다</b>
 */
record TableDocument(String fileName,
                     String sheetName,
                     String title,
                     String subtitle,
                     List<Column> columns,
                     List<Section> sections,
                     String note) {

    /**
     * @param money 숫자로 쓰고 천 단위 구분 서식을 준다. 글자로 쓰면 엑셀에서 합계가 안 잡혀
     *              받는 쪽이 계산기를 꺼낸다
     */
    record Column(String name, int width, boolean money) {

        static Column of(String name, int width) {
            return new Column(name, width, false);
        }

        static Column money(String name) {
            return new Column(name, 14, true);
        }
    }

    /**
     * 시트 안의 한 구획.
     *
     * <p><b>구획이 필요한 이유가 명세에 있다.</b> 필요경비 후보 명세서는 개인 사용 의심 거래를
     * <b>지우지 않고 별도 구획으로 분리</b>해야 한다 — 삭제하면 세무대리인이 그 판단을 다시
     * 볼 근거가 사라진다.
     *
     * @param note    구획 제목 아래 한 줄. 왜 갈라 놓았는지를 적는다
     * @param columns 이 구획만의 열. {@code null} 이면 시트의 열을 쓴다. 한 시트가 성격이
     *                다른 표 둘을 담을 때 필요하다 — 고정비 시트가 「분류별 합계」와 「월별
     *                내역」을 함께 싣는데, 열 이름을 「건수 또는 합계」처럼 뭉개면 받는 쪽이
     *                어느 칸이 무엇인지 세어 봐야 한다
     * @param rows    각 줄은 그 구획의 열과 길이가 같다
     */
    record Section(String label, String note, List<Column> columns, List<List<Object>> rows) {

        static Section of(String label, String note, List<List<Object>> rows) {
            return new Section(label, note, null, rows);
        }

        List<Column> columnsOr(List<Column> fallback) {
            return columns == null ? fallback : columns;
        }
    }
}
