package com.autocare.platform.vehicle;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Base64;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ArchiveController {
    private static final String BASE64_ACCEPT = "application/vnd.autocare.pdf-base64+json";
    private static final String FILE_NAME = "vehicle-archive-report.pdf";
    private static final int MAX_PDF_BYTES = 3 * 1024 * 1024;
    private final ObjectProvider<ArchiveService> services;
    private final ObjectProvider<ArchiveReportService> reports;
    private final ObjectProvider<ArchiveReportPdfRenderer> renderer;
    public ArchiveController(ObjectProvider<ArchiveService> services, ObjectProvider<ArchiveReportService> reports,
                             ObjectProvider<ArchiveReportPdfRenderer> renderer) {
        this.services = services; this.reports = reports; this.renderer = renderer;
    }
    private ArchiveService service() {
        ArchiveService service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "档案数据库尚未配置");
        return service;
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> databaseUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
            .body(ApiResponse.error(50300, "档案数据库暂不可用，请稍后重试"));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> requestFailure(ResponseStatusException error) {
        int status = error.getStatusCode().value();
        int code = status == 400 ? 40001 : status * 100;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error(code, error.getReason()));
    }
    @GetMapping("/api/archive/list")
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt, @RequestParam("vehicle_id") long vehicleId,
        @RequestParam(defaultValue="1") int page, @RequestParam(name="page_size",defaultValue="20") int size) {
        VehicleOwner owner = VehicleOwner.from(jwt);
        VehicleService.page(page, size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.success(service().list(owner, vehicleId, page, size)));
    }
    @GetMapping("/api/archive/report")
    public ResponseEntity<?> report(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
                                    @RequestHeader(value="Accept", required=false) String accept) {
        VehicleOwner owner = VehicleOwner.from(jwt);
        ArchiveReportFilter filter = ArchiveReportFilter.parse(request.getParameterMap());
        ArchiveReport snapshot = reportService().load(owner, filter);
        byte[] pdf;
        try { pdf = pdfRenderer().render(snapshot); }
        catch (IOException | RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "中文档案报告暂不可用，请稍后重试");
        }
        if (pdf.length == 0 || pdf.length > MAX_PDF_BYTES)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "报告文件超过导出上限，请缩小日期范围");
        if (wantsBase64(accept)) {
            var body = ApiResponse.success(new ArchiveReportEnvelope(1, FILE_NAME, MediaType.APPLICATION_PDF_VALUE,
                pdf.length, Base64.getEncoder().encodeToString(pdf)));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PDF)
            .header("Content-Disposition", "attachment; filename=\"" + FILE_NAME + "\"")
            .contentLength(pdf.length).body(pdf);
    }
    @PostMapping("/api/archive/add")
    public ResponseEntity<JsonNode> add(@AuthenticationPrincipal Jwt jwt,
        @RequestHeader(value="Idempotency-Key",required=false) String key, @RequestBody JsonNode body) {
        VehicleOwner owner = VehicleOwner.from(jwt);
        WriteIntegrityService.normalizeKey(key);
        ArchiveInput input = ArchiveInput.parse(body);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service().add(owner, key, input));
    }

    private ArchiveReportService reportService() {
        ArchiveReportService service = reports.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "档案数据库尚未配置");
        return service;
    }
    private ArchiveReportPdfRenderer pdfRenderer() {
        ArchiveReportPdfRenderer value = renderer.getIfAvailable();
        if (value == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "档案报告暂不可用");
        return value;
    }
    private static boolean wantsBase64(String accept) {
        if (accept == null || accept.isBlank()) return false;
        try {
            return MediaType.parseMediaTypes(accept).stream().anyMatch(type ->
                BASE64_ACCEPT.equalsIgnoreCase(type.getType() + "/" + type.getSubtype())
                    && "1".equals(type.getParameter("version")));
        } catch (IllegalArgumentException ignored) { return false; }
    }
    private record ArchiveReportEnvelope(
        @com.fasterxml.jackson.annotation.JsonProperty("version") int version,
        @com.fasterxml.jackson.annotation.JsonProperty("file_name") String fileName,
        @com.fasterxml.jackson.annotation.JsonProperty("content_type") String contentType,
        @com.fasterxml.jackson.annotation.JsonProperty("content_length") int contentLength,
        @com.fasterxml.jackson.annotation.JsonProperty("content_base64") String contentBase64
    ) {}
}
