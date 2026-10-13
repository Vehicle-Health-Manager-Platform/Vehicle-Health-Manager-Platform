package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.OperatorActor;
import com.autocare.platform.vehicle.VehicleOwner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController public class VehiclePlanEvidenceController {
    private final ObjectProvider<VehiclePlanEvidence> services;
    public VehiclePlanEvidenceController(ObjectProvider<VehiclePlanEvidence> services){this.services=services;}
    private VehiclePlanEvidence service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"车辆方案证据数据库尚未配置");return s;}
    private static void query(MultiValueMap<String,String> q){if(!q.isEmpty())throw ReservationInput.bad();}
    private static ResponseEntity<?> ok(Object data){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data);}
    @PostMapping(value="/api/ai/service-plan/vehicle-evidence",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> evidence(@AuthenticationPrincipal Jwt jwt,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(jwt);query(q);return ok(ApiResponse.success(service().evidence(a,VehiclePlanEvidence.request(raw))));}
    @GetMapping("/api/admin/project-applicability/{project}/{model}")
    public ResponseEntity<?> read(@AuthenticationPrincipal Jwt jwt,@PathVariable long project,@PathVariable long model,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(jwt);query(q);com.autocare.platform.service.ServiceCatalog.validateId(project);com.autocare.platform.service.ServiceCatalog.validateId(model);return ok(ApiResponse.success(service().readRule(a,project,model)));}
    @PutMapping(value="/api/admin/project-applicability/{project}/{model}",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> write(@AuthenticationPrincipal Jwt jwt,@PathVariable long project,@PathVariable long model,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(jwt);query(q);com.autocare.platform.service.ServiceCatalog.validateId(project);com.autocare.platform.service.ServiceCatalog.validateId(model);WriteIntegrityService.normalizeKey(key);var b=VehiclePlanEvidence.ruleInput(raw);return ok(service().writeRule(a,project,model,key,b));}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> failure(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"车辆方案证据暂不可用，请稍后重试"));}
}
