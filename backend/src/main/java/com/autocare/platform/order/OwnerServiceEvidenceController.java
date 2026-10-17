package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.file.PrivateFileAccessService;
import com.autocare.platform.vehicle.VehicleOwner;
import com.autocare.platform.service.ServiceCatalog;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class OwnerServiceEvidenceController {
    private final ObjectProvider<OwnerServiceEvidence> services;private final PrivateFileAccessService files;
    public OwnerServiceEvidenceController(ObjectProvider<OwnerServiceEvidence> services,PrivateFileAccessService files){this.services=services;this.files=files;}
    private OwnerServiceEvidence service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"施工证据数据库尚未配置");return s;}
    private void query(MultiValueMap<String,String> q){if(!q.isEmpty())throw ReservationInput.bad();}
    private ResponseEntity<?> ok(Object n){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(n));}
    @GetMapping("/api/order/{id}/work") public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(jwt);query(q);ServiceCatalog.validateId(id);return ok(service().detail(a,id));}
    @GetMapping("/api/order/{id}/work/files/{file}/access") public ResponseEntity<?> access(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@PathVariable long file,@RequestParam MultiValueMap<String,String> q){
        var a=VehicleOwner.from(jwt);query(q);ServiceCatalog.validateId(id);ServiceCatalog.validateId(file);var s=service();var owner=s.fileOwner(a,id,file);var signed=files.sign(owner,file);
        // Object-store I/O runs outside the read transaction; revoke/transfer/delete during it
        // must reject the response rather than returning a URL authorized before that I/O.
        if(!owner.equals(s.fileOwner(a,id,file)))throw ReservationStore.missing();
        return ok(Map.of("url",signed.url(),"expires_at",signed.expiresAt()));
    }
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"施工证据暂不可用，请刷新重试"));}
}
