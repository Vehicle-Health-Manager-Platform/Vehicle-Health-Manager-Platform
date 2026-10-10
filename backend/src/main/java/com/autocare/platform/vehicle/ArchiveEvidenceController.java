package com.autocare.platform.vehicle;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.file.PrivateFileAccessService;
import com.autocare.platform.service.ServiceCatalog;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ArchiveEvidenceController {
    private final ObjectProvider<ArchiveService> services;
    private final PrivateFileAccessService files;
    public ArchiveEvidenceController(ObjectProvider<ArchiveService> services, PrivateFileAccessService files) { this.services=services; this.files=files; }
    @GetMapping("/api/archive/{archiveId}/files/{fileId}/access")
    public ResponseEntity<?> access(@AuthenticationPrincipal Jwt jwt, @PathVariable long archiveId, @PathVariable long fileId,
        @RequestParam MultiValueMap<String,String> params) {
        var owner = VehicleOwner.from(jwt); ServiceCatalog.validateId(archiveId); ServiceCatalog.validateId(fileId);
        if (!params.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片参数无效");
        var service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "档案数据库尚未配置");
        var signed = files.sign(service.fileOwner(owner, archiveId, fileId), fileId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(Map.of("url", signed.url(), "expires_at", signed.expiresAt())));
    }
    @ExceptionHandler(DataAccessException.class) ResponseEntity<?> database() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"档案图片暂不可用"));
    }
}
