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

@RestController
public class PublicImageController {
 private final ObjectProvider<PublicImages> services;
 public PublicImageController(ObjectProvider<PublicImages> services){this.services=services;}
 private PublicImages service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"图片公开数据库尚未配置");return s;}
 private ResponseEntity<?> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(value));}
 private ResponseEntity<?> receipt(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
 private ResponseEntity<byte[]> png(byte[] bytes){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG).header("X-Content-Type-Options","nosniff").header("Content-Disposition","inline; filename=public-copy.png").body(bytes);}
 private boolean format(MultiValueMap<String,String> q){if(!q.containsKey("format"))return false;if(q.get("format").size()!=1||!"base64".equals(q.getFirst("format")))throw ReservationInput.bad();q.remove("format");return true;}
 private ResponseEntity<?> image(byte[] bytes,boolean encoded){return encoded?ok(java.util.Map.of("png_base64",java.util.Base64.getEncoder().encodeToString(bytes))):png(bytes);}
 private void id(long id){com.autocare.platform.service.ServiceCatalog.validateId(id);}
 private void empty(MultiValueMap<String,String> q){InvitationController.query(q,false);}
 private void write(String key,MultiValueMap<String,String> q){empty(q);WriteIntegrityService.normalizeKey(key);}
 @GetMapping("/api/owner/community/image-sources")public ResponseEntity<?> sources(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);if(!q.containsKey("experience_id")||q.get("experience_id").size()!=1)throw ReservationInput.bad();String reference=ExperienceModeration.publicId(q.getFirst("experience_id"));var remaining=new org.springframework.util.LinkedMultiValueMap<String,String>(q);remaining.remove("experience_id");int p=InvitationController.query(remaining,true);return ok(service().sources(a,reference,p));}
 @PostMapping(value="/api/owner/community/images/preview",consumes="application/json")public ResponseEntity<?> preview(@AuthenticationPrincipal Jwt j,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);empty(q);return ok(service().preview(a,PublicImages.input(raw,"preview")));}
 @GetMapping("/api/owner/community/images")public ResponseEntity<?> own(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);return ok(service().own(a,InvitationController.query(q,true)));}
 @PostMapping(value="/api/owner/community/images",consumes="application/json")public ResponseEntity<?> submit(@AuthenticationPrincipal Jwt j,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);write(key,q);return receipt(service().submit(a,key,PublicImages.input(raw,"submit")));}
 @PostMapping(value="/api/owner/community/images/{id}/withdraw",consumes="application/json")public ResponseEntity<?> withdraw(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);id(id);write(key,q);return receipt(service().withdraw(a,id,key,PublicImages.input(raw,"withdraw")));}
 @GetMapping("/api/owner/community/images/{id}/preview")public ResponseEntity<?> ownPreview(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);id(id);boolean encoded=format(q);empty(q);return image(service().ownerPreview(a,id),encoded);}
 @GetMapping("/api/admin/community/images")public ResponseEntity<?> pending(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);return ok(service().pending(a,InvitationController.query(q,true)));}
 @GetMapping("/api/admin/community/images/{id}/preview")public ResponseEntity<?> reviewPreview(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);id(id);boolean encoded=format(q);empty(q);return image(service().reviewPreview(a,id),encoded);}
 @PostMapping(value="/api/admin/community/images/{id}/moderate",consumes="application/json")public ResponseEntity<?> moderate(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);id(id);write(key,q);return receipt(service().moderate(a,id,key,PublicImages.input(raw,"moderate")));}
 private long vehicle(MultiValueMap<String,String> q,boolean list){if(q.size()!=(list?2:1)||!q.containsKey("vehicle_id")||q.get("vehicle_id").size()!=1||!q.getFirst("vehicle_id").matches("[1-9][0-9]{0,15}")||list&&(!q.containsKey("experience_id")||q.get("experience_id").size()!=1))throw ReservationInput.bad();try{long v=Long.parseLong(q.getFirst("vehicle_id"));id(v);return v;}catch(NumberFormatException e){throw ReservationInput.bad();}}
 @GetMapping("/api/community/images")public ResponseEntity<?> list(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);long v=vehicle(q,true);String reference=ExperienceModeration.publicId(q.getFirst("experience_id"));return ok(service().list(a,v,reference));}
 @GetMapping("/api/community/images/{publicId}")public ResponseEntity<?> bytes(@AuthenticationPrincipal Jwt j,@PathVariable String publicId,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);boolean encoded=format(q);long v=vehicle(q,false);ExperienceModeration.publicId(publicId);return image(service().bytes(a,v,publicId),encoded);}
 @ExceptionHandler(ResponseStatusException.class)ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class)ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"图片公开服务暂不可用，请重试原操作"));}
}
