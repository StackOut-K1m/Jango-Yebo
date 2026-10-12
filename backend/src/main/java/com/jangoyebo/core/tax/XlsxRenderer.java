package com.jangoyebo.core.tax;

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
import java.util.List;

/**
 * 홈택스 입력용 XLSX 렌더러 (API 14.3 {@code XLSX_HOMETAX_INPUT}).
 *
 * <p>{@link ExportDocument} 만 읽는다 — 초안·서식·수정값을 모르는 것이 이 클래스의 일이다.
 * PDF 를 붙일 때 이 자리에 렌더러를 하나 더 두면 된다.
 */
@Component
public class XlsxRenderer {

    /** 값 칸 앞에 붙는 고정 열 — 항목. 뒤에 붙는 것 — 출처·근거 건수·확인 필요. */
    private static final int LABEL_COLUMN = 0;

    public byte[] render(ExportDocument document) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("홈택스 입력");
            Styles styles = new Styles(workbook);
            int rowIndex = 0;

            rowIndex = writeHeader(sheet, styles, document, rowIndex);
            for (ExportDocument.Section section : document.sections()) {
                rowIndex = writeSection(sheet, styles, document, section, rowIndex);
            }
            writeDisclaimer(sheet, styles, document, rowIndex);

            sizeColumns(sheet, document.columns().size());
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "내보내기 파일을 만들지 못했습니다.");
        }
    }

    private int writeHeader(Sheet sheet, Styles styles, ExportDocument document, int rowIndex) {
        cell(sheet.createRow(rowIndex++), LABEL_COLUMN, document.title(), styles.title);
        cell(sheet.createRow(rowIndex++), LABEL_COLUMN, document.subtitle(), styles.subtitle);
        rowIndex++;

        Row header = sheet.createRow(rowIndex++);
        cell(header, LABEL_COLUMN, "항목", styles.header);
        int column = LABEL_COLUMN + 1;
        for (String name : document.columns()) {
            cell(header, column++, name, styles.header);
        }
        cell(header, column++, "출처", styles.header);
        cell(header, column++, "근거 건수", styles.header);
        cell(header, column, "확인 필요", styles.header);
        return rowIndex;
    }

    private int writeSection(Sheet sheet, Styles styles, ExportDocument document,
                             ExportDocument.Section section, int rowIndex) {
        rowIndex++;
        cell(sheet.createRow(rowIndex++), LABEL_COLUMN, section.label(), styles.section);
        if (section.note() != null) {
            cell(sheet.createRow(rowIndex++), LABEL_COLUMN, section.note(), styles.note);
        }

        for (ExportDocument.Row row : section.rows()) {
            Row sheetRow = sheet.createRow(rowIndex++);
            // 사람이 직접 넣는 줄은 눈에 띄어야 한다. 옮겨 적다가 지나치면 신고가 빈다.
            CellStyle labelStyle = row.manualEntry() ? styles.manualLabel : styles.label;
            cell(sheetRow, LABEL_COLUMN, row.label(), labelStyle);

            int column = LABEL_COLUMN + 1;
            for (Object value : row.values()) {
                writeValue(sheetRow, column++, value, styles);
            }
            // 열 수가 모자라면 빈 칸으로 채운다 — 뒤 열이 밀리면 표가 어긋난다.
            while (column <= document.columns().size()) {
                cell(sheetRow, column++, "", styles.text);
            }
            cell(sheetRow, column++, row.source(), styles.text);
            cell(sheetRow, column++,
                    row.basisCount() == null ? "" : String.valueOf(row.basisCount()), styles.text);
            cell(sheetRow, column, row.note() == null ? "" : row.note(), styles.note);
        }
        return rowIndex;
    }

    private void writeValue(Row row, int column, Object value, Styles styles) {
        Cell cell = row.createCell(column);
        if (value == null) {
            cell.setCellValue("");
            cell.setCellStyle(styles.text);
            return;
        }
        if (value instanceof Number number) {
            cell.setCellValue(new BigDecimal(number.toString()).doubleValue());
            cell.setCellStyle(styles.money);
            return;
        }
        cell.setCellValue(String.valueOf(value));
        cell.setCellStyle(styles.text);
    }

    private void writeDisclaimer(Sheet sheet, Styles styles, ExportDocument document, int rowIndex) {
        rowIndex++;
        cell(sheet.createRow(rowIndex), LABEL_COLUMN, document.disclaimer(), styles.note);
    }

    private void sizeColumns(Sheet sheet, int valueColumns) {
        sheet.setColumnWidth(LABEL_COLUMN, 46 * 256);
        for (int i = 1; i <= valueColumns; i++) {
            sheet.setColumnWidth(i, 18 * 256);
        }
        sheet.setColumnWidth(valueColumns + 1, 18 * 256);
        sheet.setColumnWidth(valueColumns + 2, 10 * 256);
        sheet.setColumnWidth(valueColumns + 3, 60 * 256);
    }

    private static void cell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    /** 스타일은 통합 문서당 개수 제한이 있어 한 번만 만들어 돌려 쓴다. */
    private static final class Styles {

        private final CellStyle title;
        private final CellStyle subtitle;
        private final CellStyle header;
        private final CellStyle section;
        private final CellStyle label;
        private final CellStyle manualLabel;
        private final CellStyle money;
        private final CellStyle text;
        private final CellStyle note;

        private Styles(Workbook workbook) {
            title = bold(workbook, 14);
            subtitle = plain(workbook);
            header = bold(workbook, 11);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            section = bold(workbook, 12);
            label = plain(workbook);
            manualLabel = bold(workbook, 11);
            money = plain(workbook);
            money.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
            money.setAlignment(HorizontalAlignment.RIGHT);
            text = plain(workbook);
            note = plain(workbook);
            note.setWrapText(false);
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
