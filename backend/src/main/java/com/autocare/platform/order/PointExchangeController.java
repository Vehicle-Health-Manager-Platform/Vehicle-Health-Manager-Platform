package com.autocare.platform.order;
import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.vehicle.VehicleOwner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController public class PointExchangeController {
 private final ObjectProvider<PointExchanges> exchanges;
 public PointExchangeController(ObjectProvider<PointExchanges> exchanges){this.exchanges=exchanges;}
 private PointExchanges configured(){var n=exchanges.getIfAvailable();if(n==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"积分兑换数据库尚未配置");return n;}
 @PostMapping(value="/api/point/exchange",consumes="application/json") public ResponseEntity<?> exchange(@AuthenticationPrincipal Jwt j,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);InvitationController.query(q,false);WriteIntegrityService.normalizeKey(key);var b=PointExchanges.input(raw);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(configured().exchange(a,key,b));}
 @GetMapping("/api/point/exchanges") public ResponseEntity<?> list(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);int p=InvitationController.query(q,true);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(configured().list(a,p)));}
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"积分兑换暂不可用，请重试原请求"));}
}
