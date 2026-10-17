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
@RestController public class InviteDiagnosisController {
 private final ObjectProvider<InviteDiagnosisPolicies> services;
 public InviteDiagnosisController(ObjectProvider<InviteDiagnosisPolicies> services){this.services=services;}
 InviteDiagnosisPolicies service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"解锁政策数据库尚未配置");return s;}
 @GetMapping("/api/invite/diagnosis-eligibility") public ResponseEntity<?> current(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(jwt);InvitationController.query(q,false);return InvitePolicyController.ok(ApiResponse.success(service().current(a)));}
 @GetMapping("/api/admin/invite/diagnosis-policies") public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(jwt);return InvitePolicyController.ok(ApiResponse.success(service().list(a,InvitationController.query(q,true))));}
 @PostMapping(value="/api/admin/invite/diagnosis-policies",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw){var a=OperatorActor.from(jwt);InvitationController.query(q,false);WriteIntegrityService.normalizeKey(key);return InvitePolicyController.ok(service().create(a,key,InviteDiagnosisPolicies.input(raw,false)));}
 @PutMapping(value="/api/admin/invite/diagnosis-policies/{id}",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> q,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw){var a=OperatorActor.from(jwt);CouponController.write(id,q,key);return InvitePolicyController.ok(service().update(a,id,key,InviteDiagnosisPolicies.input(raw,true)));}
 @PutMapping(value="/api/admin/invite/diagnosis-policies/{id}/status",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> status(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> q,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw){var a=OperatorActor.from(jwt);CouponController.write(id,q,key);return InvitePolicyController.ok(service().status(a,id,key,InvitePolicyInput.status(raw)));}
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"解锁政策暂不可用，请重试"));}
}
