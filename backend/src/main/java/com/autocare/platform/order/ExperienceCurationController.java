package com.autocare.platform.order;
import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.OperatorActor;
import com.autocare.platform.service.MerchantActor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController public class ExperienceCurationController {
 private final ObjectProvider<ExperienceCurations> policies;public ExperienceCurationController(ObjectProvider<ExperienceCurations> policies){this.policies=policies;}
 private ExperienceCurations configured(){var n=policies.getIfAvailable();if(n==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"经验精选数据库尚未配置");return n;}
 private ResponseEntity<?> ok(Object n){return receipt(ApiResponse.success(n));}private ResponseEntity<?> receipt(Object n){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(n);}
 private void write(long id,String key,MultiValueMap<String,String> q){if(id!=0)com.autocare.platform.service.ServiceCatalog.validateId(id);InvitationController.query(q,false);WriteIntegrityService.normalizeKey(key);}
 @GetMapping("/api/admin/community/curation-sources")public ResponseEntity<?> quotes(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);int p=InvitationController.query(q,true);return ok(configured().sources(a,p));}
 @GetMapping("/api/admin/community/curations")public ResponseEntity<?> list(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);int p=InvitationController.query(q,true);return ok(configured().list(a,p));}
 @PostMapping(value="/api/admin/community/curations",consumes="application/json")public ResponseEntity<?> create(@AuthenticationPrincipal Jwt j,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);write(0,key,q);var b=ExperienceCurations.input(raw,false);return receipt(configured().create(a,key,b));}
 @PutMapping(value="/api/admin/community/curations/{id}",consumes="application/json")public ResponseEntity<?> update(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);com.autocare.platform.service.ServiceCatalog.validateId(id);write(id,key,q);var b=ExperienceCurations.input(raw,true);return receipt(configured().update(a,id,key,b));}
 @PutMapping(value="/api/admin/community/curations/{id}/status",consumes="application/json")public ResponseEntity<?> status(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);com.autocare.platform.service.ServiceCatalog.validateId(id);write(id,key,q);var b=CouponInput.status(raw);return receipt(configured().status(a,id,key,b));}
 @GetMapping("/api/admin/community/curations/{id}")public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);com.autocare.platform.service.ServiceCatalog.validateId(id);InvitationController.query(q,false);return ok(configured().detail(a,id));}
 @GetMapping("/api/community/hot")public ResponseEntity<?> curated(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=com.autocare.platform.vehicle.VehicleOwner.from(j);if(q.size()!=1||!q.containsKey("vehicle_id")||q.get("vehicle_id").size()!=1||!q.getFirst("vehicle_id").matches("[1-9][0-9]{0,15}"))throw ReservationInput.bad();try{long vehicle=Long.parseLong(q.getFirst("vehicle_id"));com.autocare.platform.service.ServiceCatalog.validateId(vehicle);return ok(configured().curated(a,vehicle));}catch(NumberFormatException e){throw ReservationInput.bad();}}
 @ExceptionHandler(ResponseStatusException.class)ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class)ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"经验精选暂不可用，请重试原操作"));}
}
