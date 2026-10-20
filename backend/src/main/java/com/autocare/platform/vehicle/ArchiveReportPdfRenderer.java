package com.autocare.platform.vehicle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

public class ArchiveReportPdfRenderer {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(SHANGHAI);
    private static final float LEFT = 48;
    private static final float RIGHT = 48;
    private static final float BOTTOM = 54;
    private static final float WIDTH = PDRectangle.A4.getWidth() - LEFT - RIGHT;

    public byte[] render(ArchiveReport report) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            InputStream resource = ArchiveReportPdfRenderer.class.getResourceAsStream("/fonts/NotoSansSC-Regular.ttf");
            if (resource == null) throw new IOException("licensed Chinese font unavailable");
            try (resource) {
                PDType0Font font = PDType0Font.load(document, resource, true);
                document.getDocumentInformation().setTitle("本人车辆档案报告");
                PageWriter pages = new PageWriter(document, font);
                pages.newPage();
                pages.paragraph("车辆健康档案报告", 20, 7);
                pages.paragraph("生成时间：" + TIME.format(report.generatedAt()), 10, 12);
                pages.paragraph("车辆：" + display(report.modelName()), 12, 3);
                pages.paragraph("车牌：" + display(report.maskedPlate()) + "    VIN：" + display(report.maskedVin()), 10, 3);
                pages.paragraph("当前登记里程：" + (report.registeredMileage() == null ? "未提供" : report.registeredMileage() + " km")
                    + "（车辆登记值，非本次核验）", 10, 9);
                pages.paragraph("范围：" + (report.from() == null ? "全部可见档案" : report.from() + " 至 " + report.to() + "（首尾均含）"), 10, 3);
                pages.paragraph("档案条数：" + report.entries().size(), 10, 3);
                if (report.from() != null)
                    pages.paragraph("因未提供发生日期而未归入筛选范围：" + report.undatedCount() + " 条", 9, 10);
                else
                    pages.paragraph("发生日期缺失的记录会在对应条目中标明。", 9, 10);

                int index = 0;
                for (ArchiveReport.Entry entry : report.entries()) {
                    index++;
                    pages.paragraph("记录 " + index + " · " + typeName(entry.archiveType()) + " · "
                        + (entry.recordedDate() == null ? "日期未提供" : entry.recordedDate()), 12, 2);
                    pages.paragraph("标题：" + display(entry.title()), 10, 2);
                    pages.paragraph("来源：" + sourceName(entry.inputType()) + "    附件：" + entry.photoCount() + " 张", 9, 2);
                    pages.paragraph("记录里程：" + (entry.mileage() == null ? "未记录" : entry.mileage() + " km"), 9, 2);
                    if (entry.inputType() == 4) {
                        pages.paragraph("实际施工：" + entry.workMinutes() + " 分钟；配件："
                            + (entry.noParts() ? "未使用" : partSummary(entry.parts())), 9, 2);
                        if (entry.testMode()) pages.paragraph("测试施工记录（不代表真实扣款）", 9, 2);
                        pages.paragraph("报工：" + formatTime(entry.submittedAt()) + "；签字：" + formatTime(entry.signedAt())
                            + "；核销：" + formatTime(entry.redeemedAt()), 8, 2);
                    }
                    if (!entry.notes().isBlank()) pages.paragraph("备注：" + entry.notes(), 9, 4);
                    pages.gap(7);
                }
                pages.gap(5);
                pages.paragraph("说明：本报告仅汇总已有且本人可见的车辆档案。未提供的证件材料、健康评分、六项指标和趋势不会推算。照片原件请在微信小程序的本人档案中查看。", 9, 4);
                pages.close();
                document.save(output);
                // The font cmap has aliases (e.g. 车 and a CJK radical). Subsetting may
                // pick the alias for ToUnicode; preserve the actual written characters.
                return withWrittenUnicode(output.toByteArray(), pages.unicode);
            }
        }
    }

    private static byte[] withWrittenUnicode(byte[] pdf, Map<String,String> unicode) throws IOException {
        StringBuilder cmap = new StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
            + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
            + "/CMapName /OwnerArchiveUnicode def\n/CMapType 2 def\n"
            + "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");
        var entries = new ArrayList<>(unicode.entrySet());
        for (int offset=0; offset<entries.size(); offset+=100) {
            int end=Math.min(offset+100,entries.size());
            cmap.append(end-offset).append(" beginbfchar\n");
            for (var entry : entries.subList(offset,end))
                cmap.append('<').append(entry.getKey()).append("> <").append(entry.getValue()).append(">\n");
            cmap.append("endbfchar\n");
        }
        cmap.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        // Apply after subsetting so the font writer cannot overwrite this map.
        try (var document=Loader.loadPDF(pdf);var output=new ByteArrayOutputStream()) {
            var stream=new PDStream(document);
            try (var target=stream.createOutputStream(COSName.FLATE_DECODE)) {
                target.write(cmap.toString().getBytes(StandardCharsets.US_ASCII));
            }
            for (var page : document.getPages())
                for (var name : page.getResources().getFontNames())
                    page.getResources().getFont(name).getCOSObject().setItem(COSName.TO_UNICODE,stream);
            document.save(output);
            return output.toByteArray();
        }
    }

    private static String partSummary(List<ArchiveReport.Part> parts) {
        List<String> values = new ArrayList<>();
        for (ArchiveReport.Part part : parts) {
            StringBuilder value = new StringBuilder(part.name());
            if (!part.model().isBlank()) value.append(' ').append(part.model());
            if (!part.brand().isBlank()) value.append(' ').append(part.brand());
            value.append(" ×").append(part.quantity());
            values.add(value.toString());
        }
        return String.join("；", values);
    }

    private static String typeName(int type) {
        return switch (type) {
            case 1 -> "保养";
            case 2 -> "维修";
            case 3 -> "保险";
            case 4 -> "事故";
            case 5 -> "改装";
            case 6 -> "违章";
            case 7 -> "年检";
            case 8 -> "行驶证资料";
            case 9 -> "驾驶证资料";
            case 10 -> "本人故障记录";
            default -> "车辆档案";
        };
    }

    private static String sourceName(int type) {
        return switch (type) {
            case 1 -> "拍照录入";
            case 3 -> "手动录入";
            case 4 -> "已完成施工归档";
            default -> "档案记录";
        };
    }

    private static String formatTime(String value) {
        if (value == null || value.isBlank()) return "未提供";
        try { return TIME.format(java.time.Instant.parse(value)); }
        catch (RuntimeException exception) { return "未提供"; }
    }

    private static String display(String value) { return value == null || value.isBlank() ? "未提供" : value; }

    private static final class PageWriter {
        private final PDDocument document;
        private final PDType0Font font;
        private final Map<String,String> unicode = new TreeMap<>();
        private PDPageContentStream stream;
        private float y;

        private PageWriter(PDDocument document, PDType0Font font) { this.document = document; this.font = font; }

        void newPage() throws IOException {
            close();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = page.getMediaBox().getHeight() - 48;
        }

        void paragraph(String text, float size, float after) throws IOException {
            String clean = sanitize(text);
            for (String sourceLine : clean.split("\\n", -1)) {
                StringBuilder line = new StringBuilder();
                float lineWidth = 0;
                for (int offset = 0; offset < sourceLine.length();) {
                    int codePoint = sourceLine.codePointAt(offset);
                    offset += Character.charCount(codePoint);
                    String glyph = glyph(codePoint);
                    float glyphWidth = font.getStringWidth(glyph) * size / 1000;
                    if (line.length() > 0 && lineWidth + glyphWidth > WIDTH) {
                        line(line.toString(), size);
                        line.setLength(0);
                        lineWidth = 0;
                    }
                    line.append(glyph);
                    lineWidth += glyphWidth;
                }
                line(line.toString(), size);
            }
            y -= after;
        }

        void gap(float amount) { y -= amount; }

        private void line(String value, float size) throws IOException {
            if (y - size < BOTTOM) newPage();
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(LEFT, y);
            for (int offset=0; offset<value.length();) {
                int point=value.codePointAt(offset);offset+=Character.charCount(point);
                String character=new String(Character.toChars(point));
                String code=HexFormat.of().formatHex(font.encode(character));
                unicode.putIfAbsent(code,HexFormat.of().formatHex(character.getBytes(StandardCharsets.UTF_16BE)));
            }
            stream.showText(value);
            stream.endText();
            y -= size + 4;
        }

        private String glyph(int codePoint) {
            // Keep supplementary CJK characters; substitute pictographs unsupported by this report font.
            if (codePoint >= 0x1F000 && codePoint <= 0x1FAFF
                && Character.getType(codePoint) == Character.OTHER_SYMBOL) return "?";
            String value = new String(Character.toChars(codePoint));
            try {
                font.getStringWidth(value);
                return value;
            } catch (IOException | IllegalArgumentException exception) {
                return "?";
            }
        }

        private static String sanitize(String value) {
            StringBuilder output = new StringBuilder();
            for (int offset = 0; offset < value.length();) {
                int point = value.codePointAt(offset);
                offset += Character.charCount(point);
                int category = Character.getType(point);
                if (point == '\r') continue;
                if (point == '\n') output.append('\n');
                else if (point == '\t') output.append(' ');
                else if (Character.isISOControl(point) || category == Character.FORMAT) output.append(' ');
                else output.appendCodePoint(point);
            }
            return output.toString();
        }

        void close() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }
    }
}
