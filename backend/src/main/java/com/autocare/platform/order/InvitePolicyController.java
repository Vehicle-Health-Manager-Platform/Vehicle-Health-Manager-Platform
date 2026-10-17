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
@RestController public class InvitePolicyController {
 private final ObjectProvider<InviteRewardPolicies> services;
 public InvitePolicyController(ObjectProvider<InviteRewardPolicies> services){this.services=services;}
 InviteRewardPolicies service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"邀请规则数据库尚未配置");return s;}
 static ResponseEntity<?> ok(Object v){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(v);}
 @GetMapping("/api/admin/invite/reward-policies") public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(jwt);return ok(ApiResponse.success(service().list(a,InvitationController.query(q,true))));}
 @GetMapping("/api/admin/invite/reward-coupons") public ResponseEntity<?> offers(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(jwt);return ok(ApiResponse.success(service().offers(a,InvitationController.query(q,true))));}
 @PostMapping(value="/api/admin/invite/reward-policies",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw){var a=OperatorActor.from(jwt);InvitationController.query(q,false);WriteIntegrityService.normalizeKey(key);return ok(service().create(a,key,InvitePolicyInput.draft(raw,false)));}
 @PutMapping(value="/api/admin/invite/reward-policies/{id}",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> q,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw){var a=OperatorActor.from(jwt);CouponController.write(id,q,key);return ok(service().update(a,id,key,InvitePolicyInput.draft(raw,true)));}
 @PutMapping(value="/api/admin/invite/reward-policies/{id}/status",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> status(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> q,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw){var a=OperatorActor.from(jwt);CouponController.write(id,q,key);return ok(service().status(a,id,key,InvitePolicyInput.status(raw)));}
 @GetMapping("/api/invite/reward-policy") public ResponseEntity<?> current(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(jwt);if(q.size()!=1||!q.containsKey("scope")||q.get("scope").size()!=1||!java.util.Set.of("OWNER","STAFF").contains(q.getFirst("scope")))throw InvitationInput.bad();return ok(ApiResponse.success(service().current(a,q.getFirst("scope"))));}
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"邀请规则暂不可用，请重试"));}
}
