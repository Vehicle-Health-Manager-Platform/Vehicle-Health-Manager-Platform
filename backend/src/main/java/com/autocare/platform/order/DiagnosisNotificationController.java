package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.OperatorActor;
import com.autocare.platform.vehicle.VehicleOwner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController public class DiagnosisNotificationController {
 private final ObjectProvider<DiagnosisNotifications> notifications;
 public DiagnosisNotificationController(ObjectProvider<DiagnosisNotifications> notifications){this.notifications=notifications;}
 private DiagnosisNotifications configured(){var n=notifications.getIfAvailable();if(n==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"诊断消息数据库尚未配置");return n;}
 private ResponseEntity<?> list(Object a,MultiValueMap<String,String> q){int p=InvitationController.query(q,true);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(configured().list(a,p)));}
 private ResponseEntity<?> read(Object a,long id,String key,String raw,MultiValueMap<String,String> q){com.autocare.platform.service.ServiceCatalog.validateId(id);InvitationController.query(q,false);WriteIntegrityService.normalizeKey(key);CouponInput.exact(CouponInput.parse(raw));return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(configured().read(a,id,key));}
 @GetMapping("/api/owner/diagnosis/notifications") public ResponseEntity<?> owner(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){return list(VehicleOwner.from(j),q);}
 @GetMapping("/api/admin/diagnosis/notifications") public ResponseEntity<?> admin(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){return list(OperatorActor.from(j),q);}
 @PutMapping(value="/api/owner/diagnosis/notifications/{id}/read",consumes="application/json") public ResponseEntity<?> ownerRead(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){return read(VehicleOwner.from(j),id,key,raw,q);}
 @PutMapping(value="/api/admin/diagnosis/notifications/{id}/read",consumes="application/json") public ResponseEntity<?> adminRead(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){return read(OperatorActor.from(j),id,key,raw,q);}
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"诊断消息暂不可用，请重试"));}
}
