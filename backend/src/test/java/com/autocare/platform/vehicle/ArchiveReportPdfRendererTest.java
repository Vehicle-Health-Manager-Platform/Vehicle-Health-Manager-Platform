package com.autocare.platform.vehicle;

import java.time.Instant;
import java.time.LocalDate;
import java.text.Normalizer;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveReportPdfRendererTest {
    @Test void createsReadableChinesePdfWithEmbeddedFontAndWhitelistedFields() throws Exception {
        var report = new ArchiveReport("比亚迪 秦PLUS 2026", "粤B***8A", "LSV***1234",
            12345, null, null, 0, Instant.parse("2026-10-11T02:00:00Z"), List.of(
            new ArchiveReport.Entry(1, 3, LocalDate.of(2026, 10, 1), "更换机油", "已完成常规保养 😀",
                12000, 2, false, 0, false, List.of(), null, null, null),
            new ArchiveReport.Entry(2, 4, LocalDate.of(2026, 10, 2), "施工记录", "维修方案：检查发动机",
                null, 3, true, 45, true, List.of(), "2026-10-02T01:00:00Z", "2026-10-02T02:00:00Z", "2026-10-02T03:00:00Z")));
        byte[] bytes = new ArchiveReportPdfRenderer().render(report);
        assertTrue(bytes.length > 1000);
        assertArrayEquals(new byte[]{'%', 'P', 'D', 'F', '-'}, java.util.Arrays.copyOf(bytes, 5));
        try (var document = Loader.loadPDF(bytes)) {
            assertTrue(document.getNumberOfPages() >= 1);
            var extracted = new PDFTextStripper().getText(document);
            assertTrue(extracted.contains("车辆健康档案报告"), "Original Chinese characters must survive extraction");
            assertTrue(extracted.contains("生成时间"));
            var text = Normalizer.normalize(extracted, Normalizer.Form.NFKC);
            assertTrue(text.contains("健康档案报告"), "PDF 提取文本：" + text);
            assertTrue(text.contains("更换机油"));
            assertTrue(text.contains("测试施工记录"));
            assertTrue(text.contains("粤B***8A"));
            assertFalse(text.contains("😀"));
            assertFalse(text.contains("□"));
            assertTrue(text.contains("已完成常规保养 ?"));
            boolean embedded = false;
            for (var name : document.getPage(0).getResources().getFontNames()) {
                var font = document.getPage(0).getResources().getFont(name);
                if (font.getFontDescriptor() != null && font.getFontDescriptor().getFontFile2() != null) embedded = true;
            }
            assertTrue(embedded);
        }
    }
}
