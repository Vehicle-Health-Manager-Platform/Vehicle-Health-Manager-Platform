package com.autocare.platform.order;
import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.OperatorActor;
import com.autocare.platform.vehicle.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController public class HealthDashboardController {
 private final ObjectProvider<HealthPolicies> policies;private final ObjectProvider<HealthDashboard> dashboards;
 public HealthDashboardController(ObjectProvider<HealthPolicies> policies,ObjectProvider<HealthDashboard> dashboards){this.policies=policies;this.dashboards=dashboards;}
 private HealthPolicies policies(){var s=policies.getIfAvailable();if(s==null)throw unavailable();return s;}
 private HealthDashboard dashboard(){var s=dashboards.getIfAvailable();if(s==null)throw unavailable();return s;}
 private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"健康来源数据库尚未配置");}
 private static void validate(long id,MultiValueMap<String,String> query){com.autocare.platform.service.ServiceCatalog.validateId(id);if(!query.isEmpty())throw ReservationInput.bad();}
 private static ResponseEntity<?> ok(Object body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
 @GetMapping("/api/vehicle/{vehicle}/health-dashboard") public ResponseEntity<?> read(@AuthenticationPrincipal Jwt jwt,@PathVariable long vehicle,@RequestParam MultiValueMap<String,String> q){var owner=VehicleOwner.from(jwt);validate(vehicle,q);return ok(ApiResponse.success(dashboard().read(owner,vehicle)));}
 @GetMapping("/api/admin/health-policies/{model}/{metric}") public ResponseEntity<?> policy(@AuthenticationPrincipal Jwt jwt,@PathVariable long model,@PathVariable String metric,@RequestParam MultiValueMap<String,String> q){var actor=OperatorActor.from(jwt);validate(model,q);HealthObservationInput.metric(metric);return ok(ApiResponse.success(policies().read(actor,model,metric)));}
 @PutMapping(value="/api/admin/health-policies/{model}/{metric}",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> policyWrite(@AuthenticationPrincipal Jwt jwt,@PathVariable long model,@PathVariable String metric,@RequestParam MultiValueMap<String,String> q,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw){var actor=OperatorActor.from(jwt);validate(model,q);HealthObservationInput.metric(metric);WriteIntegrityService.normalizeKey(key);var body=HealthPolicyInput.input(raw,metric,java.time.LocalDate.now(ReservationInput.ZONE));return ok(policies().write(actor,model,metric,key,body));}
 @PutMapping(value="/api/vehicle/{vehicle}/health/{metric}/confirmation",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> confirm(@AuthenticationPrincipal Jwt jwt,@PathVariable long vehicle,@PathVariable String metric,@RequestParam MultiValueMap<String,String> q,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw){var owner=VehicleOwner.from(jwt);validate(vehicle,q);HealthObservationInput.metric(metric);WriteIntegrityService.normalizeKey(key);return ok(dashboard().confirm(owner,vehicle,metric,key,HealthDashboard.confirmationInput(raw)));}
 @PutMapping(value="/api/vehicle/{vehicle}/reminder-preference",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> preference(@AuthenticationPrincipal Jwt jwt,@PathVariable long vehicle,@RequestParam MultiValueMap<String,String> q,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw){var owner=VehicleOwner.from(jwt);validate(vehicle,q);WriteIntegrityService.normalizeKey(key);return ok(dashboard().savePreference(owner,vehicle,key,HealthDashboard.preferenceInput(raw)));}
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"健康来源服务暂不可用，请稍后重试"));}
}
