package com.autocare.platform.order;
import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.OperatorActor;
import com.autocare.platform.service.*;
import com.autocare.platform.vehicle.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController public class CouponController {
 private final ObjectProvider<CouponInventory> inventory;
 public CouponController(ObjectProvider<CouponInventory> inventory){this.inventory=inventory;}
 CouponInventory service(){var s=inventory.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"券数据库尚未配置");return s;}
 static ResponseEntity<?> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
 static int integer(String raw){if(raw==null||!raw.matches("[1-9][0-9]{0,6}"))throw CouponInput.bad();try{return Integer.parseInt(raw);}catch(Exception e){throw CouponInput.bad();}}
 static int[] query(MultiValueMap<String,String> q,boolean shop){for(var e:q.entrySet())if(!(e.getKey().equals("page")||e.getKey().equals("page_size")||shop&&e.getKey().equals("merchant_id"))||e.getValue().size()!=1)throw CouponInput.bad();int p=q.containsKey("page")?integer(q.getFirst("page")):1,s=q.containsKey("page_size")?integer(q.getFirst("page_size")):20;VehicleService.page(p,s);return new int[]{p,s};}
 static void write(long id,MultiValueMap<String,String> q,String key){ServiceCatalog.validateId(id);if(!q.isEmpty())throw CouponInput.bad();WriteIntegrityService.normalizeKey(key);}
 @GetMapping("/api/merchant/coupons") public ResponseEntity<?> merchant(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=MerchantActor.from(jwt);var p=query(q,false);return ok(ApiResponse.success(service().merchantList(a,p[0],p[1])));}
 @GetMapping("/api/admin/coupons") public ResponseEntity<?> admin(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(jwt);var p=query(q,false);return ok(ApiResponse.success(service().poolList(a,p[0],p[1])));}
 @GetMapping("/api/coupon/offers") public ResponseEntity<?> offers(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(jwt);var p=query(q,true);Long shop=null;if(q.containsKey("merchant_id")){try{String raw=q.getFirst("merchant_id");if(!raw.matches("[1-9][0-9]{0,15}"))throw CouponInput.bad();shop=Long.parseLong(raw);ServiceCatalog.validateId(shop);}catch(Exception e){throw CouponInput.bad();}}return ok(ApiResponse.success(service().offers(a,shop,p[0],p[1])));}
 @GetMapping("/api/coupon/mine") public ResponseEntity<?> mine(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(jwt);var p=query(q,false);return ok(ApiResponse.success(service().mine(a,p[0],p[1])));}
 @PostMapping(value="/api/merchant/coupons",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw){var a=MerchantActor.from(jwt);write(1,q,key);return ok(service().create(a,key,CouponInput.draft(raw,false)));}
 @PutMapping(value="/api/merchant/coupons/{id}",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> q,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw){var a=MerchantActor.from(jwt);write(id,q,key);return ok(service().update(a,id,key,CouponInput.draft(raw,true)));}
 @PutMapping(value="/api/merchant/coupons/{id}/status",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> status(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> q,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw){var a=MerchantActor.from(jwt);write(id,q,key);return ok(service().status(a,id,key,CouponInput.status(raw)));}
 @PutMapping(value="/api/admin/coupons/{id}/pool",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> pool(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> q,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw){var a=OperatorActor.from(jwt);write(id,q,key);return ok(service().pool(a,id,key,CouponInput.pool(raw)));}
 @PostMapping(value="/api/coupon/receive",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> receive(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw){var a=VehicleOwner.from(jwt);write(1,q,key);return ok(service().claim(a,key,CouponInput.claim(raw)));}
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"券服务暂不可用，请重试"));}
}
