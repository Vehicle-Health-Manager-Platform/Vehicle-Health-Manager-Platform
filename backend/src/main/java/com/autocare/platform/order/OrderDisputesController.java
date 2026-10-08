package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.service.ServiceCatalog;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 争议处理：商家追加处理记录，车主复核并决定是否恢复订单。 */
@RestController
public class OrderDisputesController {
    private final ObjectProvider<OrderDisputes> services;
    public OrderDisputesController(ObjectProvider<OrderDisputes> services){this.services=services;}
    private OrderDisputes configured(){var service=services.getIfAvailable();if(service==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"争议处理数据库尚未配置");return service;}
    private static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"争议处理参数无效");}
    private static ResponseEntity<?> ok(JsonNode data){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data);}
    /** Exactly {@code {"note":string}}. */
    @PostMapping("/api/merchant/orders/{id}/dispute/handle") public ResponseEntity<?> handle(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="Idempotency-Key",required=false)String key,@PathVariable long id,@RequestBody JsonNode body){
        var actor=MerchantActor.from(jwt);ServiceCatalog.validateId(id);WriteIntegrityService.normalizeKey(key);
        if(body==null||!body.isObject()||body.size()!=1||!body.has("note")||!body.get("note").isTextual()||body.get("note").textValue().isBlank())throw bad();
        return ok(configured().handle(actor,key,id,body.get("note").textValue()));
    }
    /** Exactly {@code {"order_id":integer,"decision":"ACCEPT"|"REJECT"}} and an optional {@code note}. */
    @PostMapping("/api/check/pickup/dispute/review") public ResponseEntity<?> review(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body){
        var owner=VehicleOwner.from(jwt);WriteIntegrityService.normalizeKey(key);
        if(body==null||!body.isObject()||body.size()<2||body.size()>3||!body.has("order_id")||!body.has("decision")||!body.get("decision").isTextual()||(body.has("note")&&!body.get("note").isTextual()))throw bad();
        body.fieldNames().forEachRemaining(field->{if(!Set.of("order_id","decision","note").contains(field))throw bad();});
        return ok(configured().review(owner,key,ReservationInput.id(body.get("order_id")),body.get("decision").asText(),body.has("note")?body.get("note").asText():null));
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"争议处理服务暂不可用，请使用原幂等键重试"));}
    @ExceptionHandler(ResponseStatusException.class)ResponseEntity<?> failure(ResponseStatusException error){int status=error.getStatusCode().value(),code=error instanceof FulfillmentConflict conflict?conflict.code:status==400?40001:status*100;return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(code,error.getReason()));}
}
