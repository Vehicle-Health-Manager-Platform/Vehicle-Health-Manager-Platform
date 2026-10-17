package com.autocare.platform.order;
import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.vehicle.VehicleOwner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController public class CouponTransactionsController {
 final ObjectProvider<CouponTransactions> transactions;final ObjectProvider<ReservationOrders> orders;final ObjectProvider<CouponServiceRedemption> services;
 public CouponTransactionsController(ObjectProvider<CouponTransactions> transactions,ObjectProvider<ReservationOrders> orders,ObjectProvider<CouponServiceRedemption> services){this.transactions=transactions;this.orders=orders;this.services=services;}
 static <T>T configured(ObjectProvider<T> provider){var v=provider.getIfAvailable();if(v==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"券交易数据库尚未配置");return v;}
 static ResponseEntity<?> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
 static void none(MultiValueMap<String,String> q){if(!q.isEmpty())throw CouponInput.bad();}
 @PostMapping(value="/api/order/coupon-preview",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> preview(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q,@RequestBody String raw){var a=VehicleOwner.from(jwt);none(q);var b=CouponOrderInput.preview(raw);return ok(ApiResponse.success(configured(transactions).preview(a,b)));}
 @PostMapping(value="/api/order/coupon-create",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw){var a=VehicleOwner.from(jwt);CouponController.write(1,q,key);return ok(configured(orders).createCoupon(a,key,CouponOrderInput.create(raw)));}
 @GetMapping("/api/coupon/{id}/service-redemption") public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(jwt);com.autocare.platform.service.ServiceCatalog.validateId(id);none(q);return ok(ApiResponse.success(configured(services).view(a,id)));}
 @PostMapping(value="/api/coupon/{id}/service-authorization",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> authorize(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> q,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw){var a=VehicleOwner.from(jwt);CouponController.write(id,q,key);return ok(configured(services).generate(a,id,key,CouponOrderInput.authorization(raw)));}
 @PostMapping(value="/api/merchant/coupons/service-redeem",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> redeem(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw){var a=MerchantActor.from(jwt);CouponController.write(1,q,key);return ok(configured(services).redeem(a,key,CouponOrderInput.redemption(raw)));}
 @PostMapping(value="/api/merchant/coupons/service-preview",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> previewService(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q,@RequestBody String raw){var a=MerchantActor.from(jwt);none(q);return ok(ApiResponse.success(configured(services).preview(a,CouponOrderInput.redemption(raw))));}
 @GetMapping("/api/merchant/coupons/service-redemptions") public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=MerchantActor.from(jwt);var p=CouponController.query(q,false);return ok(ApiResponse.success(configured(services).list(a,p[0],p[1])));}
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();var builder=ResponseEntity.status(s).cacheControl(CacheControl.noStore());if(e instanceof OrderRedemption.RateLimited r)builder.header("Retry-After",Integer.toString(r.retry));return builder.body(ApiResponse.error(e instanceof ReservationConflict c?c.code:s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"券交易服务暂不可用，请保留原请求重试"));}
}
