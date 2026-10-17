package com.autocare.platform.order;
import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.vehicle.VehicleOwner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController public class PointExchangeOffersController {
 private final ObjectProvider<PointExchangeOffers> offers;
 public PointExchangeOffersController(ObjectProvider<PointExchangeOffers> offers){this.offers=offers;}
 @GetMapping("/api/point/exchange-catalog") public ResponseEntity<?> list(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);int p=InvitationController.query(q,true);var service=offers.getIfAvailable();if(service==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"积分兑换目录数据库尚未配置");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.list(a,p)));}
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"兑换目录暂不可用，请刷新后核对"));}
}
