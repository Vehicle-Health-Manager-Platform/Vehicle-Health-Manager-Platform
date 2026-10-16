package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.vehicle.VehicleOwner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController public class ServiceNotificationController {
 private final ObjectProvider<ServiceNotifications> service;private final String appId;
 public ServiceNotificationController(ObjectProvider<ServiceNotifications> service,@Value("${WECHAT_APP_ID:}")String appId){this.service=service;this.appId=appId;}
 private ServiceNotifications configured(){var s=service.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"通知数据库尚未配置");return s;}
 private ResponseEntity<?> list(Object actor,MultiValueMap<String,String> q){int page=InvitationController.query(q,true);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(configured().list(actor,page)));}
 private ResponseEntity<?> read(Object actor,long id,String key,String raw,MultiValueMap<String,String> q){com.autocare.platform.service.ServiceCatalog.validateId(id);InvitationController.query(q,false);WriteIntegrityService.normalizeKey(key);CouponInput.exact(CouponInput.parse(raw));return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(configured().read(actor,id,key));}
 @GetMapping("/api/owner/notifications") public ResponseEntity<?> owner(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){return list(VehicleOwner.from(j),q);}
 @GetMapping("/api/merchant/notifications") public ResponseEntity<?> merchant(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){return list(MerchantActor.from(j),q);}
 @GetMapping("/api/tech/notifications") public ResponseEntity<?> tech(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){return list(TechnicianActor.from(j,appId),q);}
 @PutMapping(value="/api/owner/notifications/{id}/read",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> ownerRead(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){return read(VehicleOwner.from(j),id,key,raw,q);}
 @PutMapping(value="/api/merchant/notifications/{id}/read",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> merchantRead(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){return read(MerchantActor.from(j),id,key,raw,q);}
 @PutMapping(value="/api/tech/notifications/{id}/read",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> techRead(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){return read(TechnicianActor.from(j,appId),id,key,raw,q);}
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"站内通知暂不可用，请重试"));}
}
