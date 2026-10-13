package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.vehicle.VehicleOwner;
import com.autocare.platform.service.ServiceCatalog;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PublicRatingsController {
    private final ObjectProvider<PublicRatings> services;
    private static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public PublicRatingsController(ObjectProvider<PublicRatings> services){this.services=services;}
    private PublicRatings service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"公开星级服务尚未配置");return s;}
    private static ResponseEntity<?> ok(Object data){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data);}
    private static void noQuery(MultiValueMap<String,String> q){if(!q.isEmpty())throw ReservationInput.bad();}
    @GetMapping("/api/order/{id}/review-publication")
    public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> q){var owner=VehicleOwner.from(jwt);ServiceCatalog.validateId(id);noQuery(q);return ok(ApiResponse.success(service().detail(owner,id)));}
    @PutMapping(value="/api/order/{id}/review-publication",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){
        var owner=VehicleOwner.from(jwt);ServiceCatalog.validateId(id);noQuery(q);WriteIntegrityService.normalizeKey(key);JsonNode body;
        try{if(raw.length()>512)throw ReservationInput.bad();body=JSON.readTree(raw);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw ReservationInput.bad();}
        if(body==null||!body.isObject()||body.size()!=2||!body.has("publish")||!body.has("expected_version")||!body.get("publish").isBoolean()||!body.get("expected_version").isIntegralNumber()||!body.get("expected_version").canConvertToLong())throw ReservationInput.bad();
        long expected=body.get("expected_version").longValue();if(expected<0||expected>9007199254740991L)throw ReservationInput.bad();
        return ok(service().update(owner,id,key,body.get("publish").booleanValue(),expected));
    }
    private static int page(MultiValueMap<String,String> q,String name,int fallback){var values=q.get(name);if(values==null)return fallback;if(values.size()!=1||!values.get(0).matches("[1-9][0-9]{0,6}"))throw ReservationInput.bad();try{return Integer.parseInt(values.get(0));}catch(NumberFormatException e){throw ReservationInput.bad();}}
    @GetMapping("/api/service/merchant/{id}/ratings")
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> q){var owner=VehicleOwner.from(jwt);ServiceCatalog.validateId(id);if(!Set.of("page","page_size").containsAll(q.keySet()))throw ReservationInput.bad();int page=page(q,"page",1),size=page(q,"page_size",20);if(page>1000000||size>50)throw ReservationInput.bad();return ok(ApiResponse.success(service().list(owner,id,page,size)));}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> failure(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"公开星级服务暂不可用，请稍后重试"));}
}
