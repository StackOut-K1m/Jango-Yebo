package com.jangoyebo.core.export;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 가로형 레코드 표를 XLSX 한 장으로 그린다 (API 14.2).
 *
 * <p>14.3 의 {@code XlsxRenderer} 와 나란히 서는 두 번째 렌더러다. 그쪽은 「홈택스 입력」
 * 시트 하나에 서식 항목을 세로로 세우도록 열이 고정돼 있어 여기에 쓸 수 없다.
 *
 * <p><b>금액은 글자가 아니라 숫자로 쓴다.</b> 받는 쪽이 세무대리인이라 시트에서 곧바로 합계를
 * 잡는다 — 글자로 쓰면 그 자리에서 계산이 멈춘다.
 *
 * <p>행 수는 사업장 한 곳의 한 해 거래라 수천 행을 넘지 않는다. 그 가정이 깨지면
 * {@code SXSSFWorkbook} 으로 바꿔야 한다.
 */
@Component
public class TableXlsxRenderer {

    public byte[] render(TableDocument document) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet(document.sheetName());
            Styles styles = new Styles(workbook);
            int rowIndex = 0;

            cell(sheet.createRow(rowIndex++), 0, document.title(), styles.title);
            cell(sheet.createRow(rowIndex++), 0, document.subtitle(), styles.subtitle);
            rowIndex++;

            for (TableDocument.Section section : document.sections()) {
                rowIndex = writeSection(sheet, styles, document, section, rowIndex);
            }

            rowIndex++;
            cell(sheet.createRow(rowIndex), 0, document.note(), styles.note);
            sizeColumns(sheet, document);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "시트를 만들지 못했습니다.");
        }
    }

    private int writeSection(Sheet sheet, Styles styles, TableDocument document,
                             TableDocument.Section section, int rowIndex) {
        if (section.label() != null) {
            cell(sheet.createRow(rowIndex++), 0, section.label(), styles.section);
        }
        if (section.note() != null) {
            cell(sheet.createRow(rowIndex++), 0, section.note(), styles.note);
        }

        List<TableDocument.Column> columns = section.columnsOr(document.columns());
        Row header = sheet.createRow(rowIndex++);
        int column = 0;
        for (TableDocument.Column definition : columns) {
            cell(header, column++, definition.name(), styles.header);
        }

        if (section.rows().isEmpty()) {
            // 빈 표를 그대로 두면 「조회가 안 됐다」로 읽힌다. 없었다는 것을 적는다.
            cell(sheet.createRow(rowIndex++), 0, "해당하는 거래가 없습니다.", styles.note);
            return rowIndex + 1;
        }

        for (List<Object> values : section.rows()) {
            Row row = sheet.createRow(rowIndex++);
            for (int i = 0; i < columns.size(); i++) {
                Object value = i < values.size() ? values.get(i) : null;
                writeValue(row, i, value, columns.get(i), styles);
            }
        }
        return rowIndex + 1;
    }

    private void writeValue(Row row, int column, Object value, TableDocument.Column definition,
                            Styles styles) {
        Cell cell = row.createCell(column);
        if (value == null) {
            cell.setCellValue("");
            cell.setCellStyle(styles.text);
            return;
        }
        if (definition.money() && value instanceof Number number) {
            cell.setCellValue(new BigDecimal(number.toString()).doubleValue());
            cell.setCellStyle(styles.money);
            return;
        }
        cell.setCellValue(String.valueOf(value));
        cell.setCellStyle(styles.text);
    }

    /**
     * 구획마다 열이 다를 수 있으므로 <b>같은 자리에서 가장 넓은 것</b>을 쓴다. 한쪽만 보고
     * 정하면 다른 구획의 글자가 잘린다.
     */
    private void sizeColumns(Sheet sheet, TableDocument document) {
        Map<Integer, Integer> widths = new LinkedHashMap<>();
        for (TableDocument.Section section : document.sections()) {
            List<TableDocument.Column> columns = section.columnsOr(document.columns());
            for (int i = 0; i < columns.size(); i++) {
                widths.merge(i, columns.get(i).width(), Math::max);
            }
        }
        widths.forEach((index, width) -> sheet.setColumnWidth(index, width * 256));
    }

    private static void cell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    /** 스타일은 통합 문서당 개수 제한이 있어 한 번만 만들어 돌려 쓴다. */
    private static final class Styles {

        private final CellStyle title;
        private final CellStyle subtitle;
        private final CellStyle section;
        private final CellStyle header;
        private final CellStyle money;
        private final CellStyle text;
        private final CellStyle note;

        private Styles(Workbook workbook) {
            title = bold(workbook, 14);
            subtitle = plain(workbook);
            section = bold(workbook, 12);
            header = bold(workbook, 11);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            money = plain(workbook);
            money.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
            money.setAlignment(HorizontalAlignment.RIGHT);
            text = plain(workbook);
            note = plain(workbook);
        }

        private static CellStyle plain(Workbook workbook) {
            CellStyle style = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setFontName("맑은 고딕");
            style.setFont(font);
            return style;
        }

        private static CellStyle bold(Workbook workbook, int points) {
            CellStyle style = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setFontName("맑은 고딕");
            font.setBold(true);
            font.setFontHeightInPoints((short) points);
            style.setFont(font);
            return style;
        }
    }
}
