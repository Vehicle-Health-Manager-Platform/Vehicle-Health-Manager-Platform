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

@RestController public class PointTaskPolicyController {
 private final ObjectProvider<PointTaskPolicies> policies;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private PointLedger ledger;
 public PointTaskPolicyController(ObjectProvider<PointTaskPolicies> policies){this.policies=policies;}
 private PointTaskPolicies configured(){var n=policies.getIfAvailable();if(n==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"积分规则数据库尚未配置");return n;}
 private ResponseEntity<?> ok(Object n){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(n));}
 private ResponseEntity<?> receipt(Object n){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(n);}
 private void write(long id,String key,MultiValueMap<String,String> q){if(id!=0)com.autocare.platform.service.ServiceCatalog.validateId(id);InvitationController.query(q,false);WriteIntegrityService.normalizeKey(key);}
 @GetMapping("/api/point/tasks") public ResponseEntity<?> current(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);InvitationController.query(q,false);return ok(ledger==null?configured().current(a):ledger.rules(a));}
 @GetMapping("/api/admin/point/task-policies") public ResponseEntity<?> list(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);int p=InvitationController.query(q,true);return ok(configured().list(a,p));}
 @PostMapping(value="/api/admin/point/task-policies",consumes="application/json") public ResponseEntity<?> create(@AuthenticationPrincipal Jwt j,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);write(0,key,q);var body=PointTaskPolicies.input(raw,false);return receipt(configured().create(a,key,body));}
 @PutMapping(value="/api/admin/point/task-policies/{id}",consumes="application/json") public ResponseEntity<?> update(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);com.autocare.platform.service.ServiceCatalog.validateId(id);write(id,key,q);var body=PointTaskPolicies.input(raw,true);return receipt(configured().update(a,id,key,body));}
 @PutMapping(value="/api/admin/point/task-policies/{id}/status",consumes="application/json") public ResponseEntity<?> status(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);com.autocare.platform.service.ServiceCatalog.validateId(id);write(id,key,q);var body=CouponInput.status(raw);return receipt(configured().status(a,id,key,body));}
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"积分规则暂不可用，请重试原操作"));}
}
