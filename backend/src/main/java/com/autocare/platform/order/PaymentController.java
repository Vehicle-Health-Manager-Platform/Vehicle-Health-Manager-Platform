package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.service.ServiceCatalog;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PaymentController {
    final ObjectProvider<PaymentService> services;final PaymentChannels channels;
    public PaymentController(ObjectProvider<PaymentService> services,PaymentChannels channels){this.services=services;this.channels=channels;}
    PaymentService service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"支付数据库尚未配置");return s;}
    @PostMapping("/api/payments/create") ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body){var owner=VehicleOwner.from(jwt);WriteIntegrityService.normalizeKey(key);ReservationInput.fields(body,"order_id","channel");long id=ReservationInput.id(body.get("order_id"));if(!body.path("channel").isTextual())throw ReservationInput.bad();String channel=body.path("channel").asText();channels.create(channel);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service().create(owner,key,id,channel));}
    @GetMapping("/api/payments/{id}") ResponseEntity<?> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id){var owner=VehicleOwner.from(jwt);ServiceCatalog.validateId(id);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service().detail(owner,id)));}
    @PostMapping(value="/api/payments/callback/LOCAL_TEST",consumes=MediaType.APPLICATION_JSON_VALUE) ResponseEntity<?> callback(HttpServletRequest request,@RequestHeader(value="X-Test-Timestamp",required=false)String timestamp,@RequestHeader(value="X-Test-Nonce",required=false)String nonce,@RequestHeader(value="X-Test-Signature",required=false)String signature)throws IOException{
        var adapter=channels.callback();byte[] raw=request.getInputStream().readNBytes(16385);var notice=adapter.verify(raw,timestamp,nonce,signature);service().notify(notice);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("code","SUCCESS"));
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"支付服务暂不可用"));}
    @ExceptionHandler(ResponseStatusException.class)ResponseEntity<?> failure(ResponseStatusException e){int status=e.getStatusCode().value();return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(status==400?40001:status*100,e.getReason()));}
}
