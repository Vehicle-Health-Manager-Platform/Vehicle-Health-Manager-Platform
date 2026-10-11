package com.autocare.platform.order;
import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.service.*;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
public class OrderRefundsController {
    final ObjectProvider<OrderRefunds> services;final ObjectProvider<RefundRecovery> recoveries;final RefundChannels channels;
    public OrderRefundsController(ObjectProvider<OrderRefunds> services,ObjectProvider<RefundRecovery> recoveries,RefundChannels channels){this.services=services;this.recoveries=recoveries;this.channels=channels;}
    OrderRefunds service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"退款数据库尚未配置");return s;}
    RefundRecovery recovery(){var s=recoveries.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"退款数据库尚未配置");return s;}
    static ResponseEntity<?> ok(Object body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
    @GetMapping("/api/order/{id}/refund") Object owner(@AuthenticationPrincipal Jwt jwt,@PathVariable long id){var owner=VehicleOwner.from(jwt);ServiceCatalog.validateId(id);return ok(ApiResponse.success(service().detail(owner,null,id)));}
    @PostMapping("/api/order/{id}/refund") Object request(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body){var owner=VehicleOwner.from(jwt);ServiceCatalog.validateId(id);WriteIntegrityService.normalizeKey(key);ReservationInput.fields(body,"reason");return ok(service().request(owner,key,id,LocalTestRefundChannel.str(body,"reason")));}
    @PostMapping("/api/order/{id}/refund/withdraw") Object withdraw(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body){var owner=VehicleOwner.from(jwt);ServiceCatalog.validateId(id);WriteIntegrityService.normalizeKey(key);ReservationInput.fields(body,"revision");return ok(service().withdraw(owner,key,id,ReservationInput.id(body.get("revision"))));}
    @GetMapping("/api/merchant/orders/{id}/refund") Object merchant(@AuthenticationPrincipal Jwt jwt,@PathVariable long id){var actor=MerchantActor.from(jwt);OrderRefunds.manager(actor);ServiceCatalog.validateId(id);return ok(ApiResponse.success(service().detail(null,actor,id)));}
    @PostMapping("/api/merchant/orders/{id}/refund/decision") Object decide(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body){var actor=MerchantActor.from(jwt);OrderRefunds.manager(actor);ServiceCatalog.validateId(id);WriteIntegrityService.normalizeKey(key);if(body!=null&&body.has("note")){ReservationInput.fields(body,"revision","decision","note");return ok(service().decide(actor,key,id,ReservationInput.id(body.get("revision")),LocalTestRefundChannel.str(body,"decision"),LocalTestRefundChannel.str(body,"note")));}ReservationInput.fields(body,"revision","decision");return ok(service().decide(actor,key,id,ReservationInput.id(body.get("revision")),LocalTestRefundChannel.str(body,"decision"),null));}
    @PostMapping("/api/merchant/orders/{id}/refund/reconcile") Object reconcile(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestBody JsonNode body){var actor=MerchantActor.from(jwt);OrderRefunds.manager(actor);ServiceCatalog.validateId(id);ReservationInput.fields(body);return ok(ApiResponse.success(recovery().reconcile(actor,id)));}
    @PostMapping(value="/api/refunds/callback/LOCAL_TEST",consumes="application/json") Object callback(HttpServletRequest request,@RequestHeader(value="X-Test-Timestamp",required=false)String time,@RequestHeader(value="X-Test-Nonce",required=false)String nonce,@RequestHeader(value="X-Test-Signature",required=false)String signature)throws java.io.IOException{var channel=channels.callback();var n=channel.verify(request.getInputStream().readNBytes(16385),time,nonce,signature);recovery().notify(n);return ok(Map.of("code","SUCCESS"));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) Object database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"退款服务暂不可用，请使用原幂等键重试"));}
    @ExceptionHandler(ResponseStatusException.class) Object failure(ResponseStatusException error){int status=error.getStatusCode().value();return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(status==400?40001:status*100,error.getReason()));}
}
