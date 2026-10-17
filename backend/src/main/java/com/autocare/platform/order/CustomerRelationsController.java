package com.autocare.platform.order;
import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.service.MerchantActor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController public class CustomerRelationsController {
 private final ObjectProvider<CustomerRelations> relations;
 public CustomerRelationsController(ObjectProvider<CustomerRelations> relations){this.relations=relations;}
 private CustomerRelations service(){var n=relations.getIfAvailable();if(n==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"客户关系数据库尚未配置");return n;}
 private MerchantActor actor(Jwt jwt){var a=MerchantActor.from(jwt);if(!a.manager())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅店长可读取本店服务关系");return a;}
 private ResponseEntity<?> ok(Object n){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(n));}
 @GetMapping("/api/merchant/customer-relations")public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=actor(jwt);int p=InvitationController.query(q,true);return ok(service().list(a,p));}
 @GetMapping("/api/merchant/customer-relations/{id}")public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> q){var a=actor(jwt);com.autocare.platform.service.ServiceCatalog.validateId(id);int p=InvitationController.query(q,true);return ok(service().detail(a,id,p));}
 @ExceptionHandler(ResponseStatusException.class)ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class)ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"服务关系来源暂不可读取，请刷新重试"));}
}
