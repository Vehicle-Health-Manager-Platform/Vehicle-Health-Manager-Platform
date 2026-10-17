package com.autocare.platform.order;
import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.OperatorActor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController public class PointExchangeCatalogController {
 private final ObjectProvider<PointExchangeCatalog> catalogs;
 public PointExchangeCatalogController(ObjectProvider<PointExchangeCatalog> catalogs){this.catalogs=catalogs;}
 private PointExchangeCatalog configured(){var n=catalogs.getIfAvailable();if(n==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"积分兑换目录数据库尚未配置");return n;}
 private ResponseEntity<?> ok(Object n){return receipt(ApiResponse.success(n));}
 private ResponseEntity<?> receipt(Object n){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(n);}
 private void write(long id,String key,MultiValueMap<String,String> q){if(id!=0)com.autocare.platform.service.ServiceCatalog.validateId(id);InvitationController.query(q,false);WriteIntegrityService.normalizeKey(key);}
 @GetMapping("/api/admin/point/exchange-sources") public ResponseEntity<?> sources(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);int p=InvitationController.query(q,true);return ok(configured().sources(a,p));}
 @GetMapping("/api/admin/point/exchange-catalog") public ResponseEntity<?> list(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);int p=InvitationController.query(q,true);return ok(configured().list(a,p));}
 @PostMapping(value="/api/admin/point/exchange-catalog",consumes="application/json") public ResponseEntity<?> create(@AuthenticationPrincipal Jwt j,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);write(0,key,q);var b=PointExchangeCatalog.input(raw,false);return receipt(configured().create(a,key,b));}
 @PutMapping(value="/api/admin/point/exchange-catalog/{id}",consumes="application/json") public ResponseEntity<?> update(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);com.autocare.platform.service.ServiceCatalog.validateId(id);write(id,key,q);var b=PointExchangeCatalog.input(raw,true);return receipt(configured().update(a,id,key,b));}
 @PutMapping(value="/api/admin/point/exchange-catalog/{id}/status",consumes="application/json") public ResponseEntity<?> status(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);com.autocare.platform.service.ServiceCatalog.validateId(id);write(id,key,q);var b=CouponInput.status(raw);return receipt(configured().status(a,id,key,b));}
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"积分兑换目录暂不可用，请重试原操作"));}
}
