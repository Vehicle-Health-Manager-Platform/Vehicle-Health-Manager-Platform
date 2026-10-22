package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 取车对比：接车基线与完工证据的只读对照。交付动作本身仍由核销完成。 */
@RestController
public class DeliveryCompareController {
    private final ObjectProvider<DeliveryCompare> services;
    public DeliveryCompareController(ObjectProvider<DeliveryCompare> services){this.services=services;}
    private DeliveryCompare service(){var value=services.getIfAvailable();if(value==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"取车对比尚未配置");return value;}
    private static ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"取车对比请求无效");}
    private static Object actor(Jwt jwt){return jwt!=null&&"OWNER".equals(jwt.getClaimAsString("role"))?VehicleOwner.from(jwt):MerchantActor.from(jwt);}
    private static ResponseEntity<?> ok(Object data){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));}
    /** Exactly {@code {"order_id":<integer>}}; nothing else and no query parameters. */
    @PostMapping("/api/check/delivery/compare")
    public ResponseEntity<?> compare(@AuthenticationPrincipal Jwt jwt,@RequestBody(required=false) JsonNode body,@RequestParam MultiValueMap<String,String> query){
        Object actor=actor(jwt);
        if(!query.isEmpty())throw invalid();
        if(body==null||!body.isObject()||body.size()!=1||!body.has("order_id"))throw invalid();
        var id=body.get("order_id");
        if(id==null||!id.isIntegralNumber()||!id.canConvertToLong())throw invalid();
        long order=id.longValue();
        if(order<1||order>9007199254740991L)throw invalid();
        return ok(service().compare(actor,order));
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"取车对比暂不可用，请稍后重试"));}
    @ExceptionHandler(ResponseStatusException.class)ResponseEntity<?> failure(ResponseStatusException error){int status=error.getStatusCode().value(),code=error instanceof FulfillmentConflict conflict?conflict.code:status==400?40001:status*100;return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(code,error.getReason()));}
}
