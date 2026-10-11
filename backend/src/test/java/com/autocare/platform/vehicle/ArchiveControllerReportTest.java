package com.autocare.platform.vehicle;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArchiveControllerReportTest {
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ArchiveService> archives = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ArchiveReportService> reportServices = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ArchiveReportPdfRenderer> renderers = mock(ObjectProvider.class);
    private final ArchiveReportService reportService = mock(ArchiveReportService.class);
    private final ArchiveReportPdfRenderer renderer = mock(ArchiveReportPdfRenderer.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private ArchiveController controller;
    private ArchiveReport report;
    private byte[] pdf;

    @BeforeEach void setUp() throws Exception {
        when(reportServices.getIfAvailable()).thenReturn(reportService);
        when(renderers.getIfAvailable()).thenReturn(renderer);
        controller = new ArchiveController(archives, reportServices, renderers);
        report = new ArchiveReport("比亚迪 秦PLUS", "粤B***8A", "LSV***1234", 12000,
            null, null, 0, Instant.parse("2026-10-11T02:00:00Z"), List.of());
        pdf = new byte[]{'%', 'P', 'D', 'F', '-', '1'};
        when(reportService.load(any(VehicleOwner.class), any(ArchiveReportFilter.class))).thenReturn(report);
        when(renderer.render(report)).thenReturn(pdf);
    }

    @Test void returnsPrivatePdfWithFixedDownloadName() throws Exception {
        var response = controller.report(ownerJwt(), request(), null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("attachment; filename=\"vehicle-archive-report.pdf\"",
            response.getHeaders().getFirst("Content-Disposition"));
        assertArrayEquals(pdf, (byte[]) response.getBody());
    }

    @Test void wrapsSamePdfBytesInVersionedCloudRunEnvelope() throws Exception {
        var response = controller.report(ownerJwt(), request(),
            "application/vnd.autocare.pdf-base64+json;version=1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        var data = mapper.valueToTree(response.getBody()).path("data");
        assertEquals(1, data.path("version").asInt());
        assertEquals("vehicle-archive-report.pdf", data.path("file_name").asText());
        assertEquals("application/pdf", data.path("content_type").asText());
        assertEquals(pdf.length, data.path("content_length").asInt());
        assertArrayEquals(pdf, Base64.getDecoder().decode(data.path("content_base64").asText()));
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.addParameter("vehicle_id", "12");
        request.addParameter("from", "2026-10-01");
        request.addParameter("to", "2026-10-31");
        return request;
    }

    private static Jwt ownerJwt() {
        return Jwt.withTokenValue("test-token").header("alg", "none").subject("1").claim("jti", "owner-session")
            .claim("subject_type", "user").claim("role", "OWNER")
            .expiresAt(Instant.now().plusSeconds(600)).build();
    }
}
