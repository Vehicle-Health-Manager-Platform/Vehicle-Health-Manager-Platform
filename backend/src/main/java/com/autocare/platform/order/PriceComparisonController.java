package com.autocare.platform.order;
import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.gateway.identity.OperatorActor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController public class PriceComparisonController {
 private final ObjectProvider<PriceComparisons> comparisons;
 public PriceComparisonController(ObjectProvider<PriceComparisons> comparisons){this.comparisons=comparisons;}
 private PriceComparisons configured(){var n=comparisons.getIfAvailable();if(n==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"价格比较数据库尚未配置");return n;}
 private ResponseEntity<?> ok(Object n){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(n));}
 @GetMapping("/api/admin/price-monitor/comparisons")public ResponseEntity<?> list(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);int p=InvitationController.query(q,true);return ok(configured().list(a,p));}
 @GetMapping("/api/admin/price-monitor/comparisons/{id}")public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);com.autocare.platform.service.ServiceCatalog.validateId(id);InvitationController.query(q,false);return ok(configured().detail(a,id));}
 @ExceptionHandler(ResponseStatusException.class)ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class)ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"价格来源暂不可读取，请刷新重试"));}
}
