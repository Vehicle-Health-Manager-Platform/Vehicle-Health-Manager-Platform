package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.file.*;
import com.autocare.platform.service.*;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/api/check/pickup")
public class PickupController {
    private final ObjectProvider<PickupInspection> services;private final PrivateFileAccessService access;
    public PickupController(ObjectProvider<PickupInspection> services,PrivateFileAccessService access){this.services=services;this.access=access;}
    private PickupInspection service(){var service=services.getIfAvailable();if(service==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"接车服务尚未配置");return service;}
    private static Object actor(Jwt jwt){return jwt!=null&&"OWNER".equals(jwt.getClaimAsString("role"))?VehicleOwner.from(jwt):MerchantActor.from(jwt);}
    private static ResponseEntity<?> ok(Object data){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));}
    @GetMapping("/context")public ResponseEntity<?> context(@AuthenticationPrincipal Jwt jwt,@RequestParam("order_id")long order){ServiceCatalog.validateId(order);return ok(service().context(MerchantActor.from(jwt),order));}
    @PostMapping("/submit")public ResponseEntity<?> submit(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service().submit(MerchantActor.from(jwt),key,PickupInput.parse(body)));}
    @GetMapping("/{order}")public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long order){ServiceCatalog.validateId(order);return ok(service().detail(actor(jwt),order));}
    @GetMapping("/{order}/files/{file}/access")public ResponseEntity<?> file(@AuthenticationPrincipal Jwt jwt,@PathVariable long order,@PathVariable long file){ServiceCatalog.validateId(order);ServiceCatalog.validateId(file);var signed=access.sign(service().fileOwner(actor(jwt),order,file),file);return ok(Map.of("url",signed.url(),"expires_at",signed.expiresAt()));}
    @ExceptionHandler(ResponseStatusException.class)ResponseEntity<?> failure(ResponseStatusException e){int status=e.getStatusCode().value();return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(status==400?40001:status*100,e.getReason()));}
    @ExceptionHandler(UploadHttpException.class)ResponseEntity<?> upload(UploadHttpException e){var response=ResponseEntity.status(e.status()).cacheControl(CacheControl.noStore());if(e.retry()>0)response.header("Retry-After",String.valueOf(e.retry()));return response.body(ApiResponse.error(e.status()*100,e.getMessage()));}
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,UploadException.class})ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"接车服务暂不可用，请使用原幂等键重试"));}
}
