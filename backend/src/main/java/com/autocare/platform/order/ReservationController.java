package com.autocare.platform.order;
import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.service.*;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ReservationController {
    final ObjectProvider<ReservationSlots> slots;final ObjectProvider<ReservationOrders> orders;
    public ReservationController(ObjectProvider<ReservationSlots> slots,ObjectProvider<ReservationOrders> orders){this.slots=slots;this.orders=orders;}
    private static <T>T configured(ObjectProvider<T> provider){T value=provider.getIfAvailable();if(value==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"预约数据库尚未配置");return value;}
    private static ResponseEntity<?> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(value));}
    private static ResponseEntity<?> saved(JsonNode value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    private static void key(String key){WriteIntegrityService.normalizeKey(key);}
    @GetMapping("/api/merchant/slots") public ResponseEntity<?> own(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false)String date,@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size){var actor=MerchantActor.from(jwt);ServiceCatalog.validate(null,page,size);return ok(configured(slots).own(actor,date,page,size));}
    @PostMapping("/api/merchant/slots") public ResponseEntity<?> publish(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body){var actor=MerchantActor.from(jwt);key(key);var input=ReservationInput.Slot.parse(body);return saved(configured(slots).publish(actor,key,input));}
    @PostMapping("/api/merchant/slots/{id}/close") public ResponseEntity<?> close(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="Idempotency-Key",required=false)String key,@PathVariable long id,@RequestBody JsonNode body){var actor=MerchantActor.from(jwt);key(key);ServiceCatalog.validateId(id);ReservationInput.fields(body);return saved(configured(slots).close(actor,key,id));}
    @GetMapping("/api/order/slots") public ResponseEntity<?> available(@AuthenticationPrincipal Jwt jwt,@RequestParam(name="merchant_id")long merchant,@RequestParam(name="project_id")long project,@RequestParam String date,@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size){var actor=VehicleOwner.from(jwt);ServiceCatalog.validateId(merchant);ServiceCatalog.validateId(project);ServiceCatalog.validate(null,page,size);return ok(configured(slots).available(actor,merchant,project,date,page,size));}
    @GetMapping("/api/order/quote/{id}") public ResponseEntity<?> quote(@AuthenticationPrincipal Jwt jwt,@PathVariable long id){var actor=VehicleOwner.from(jwt);ServiceCatalog.validateId(id);return ok(configured(orders).quote(actor,id));}
    @PostMapping("/api/order/create") public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body){var actor=VehicleOwner.from(jwt);key(key);var input=ReservationInput.Booking.parse(body);return saved(configured(orders).create(actor,key,input));}
    @GetMapping("/api/order/list") public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false)String status,@RequestParam(defaultValue="1")int page,@RequestParam(name="page_size",defaultValue="20")int size){var actor=VehicleOwner.from(jwt);ServiceCatalog.validate(null,page,size);if(status!=null && !OrderStatus.known(status))throw ReservationInput.bad();return ok(configured(orders).list(actor,status,page,size));}
    @GetMapping("/api/order/{id}") public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id){var actor=VehicleOwner.from(jwt);ServiceCatalog.validateId(id);return ok(configured(orders).detail(actor,id));}
    @PostMapping("/api/order/cancel") public ResponseEntity<?> cancel(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body){var actor=VehicleOwner.from(jwt);key(key);ReservationInput.fields(body,"order_id");long id=ReservationInput.id(body.get("order_id"));return saved(configured(orders).cancel(actor,key,id));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)public ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"预约服务暂不可用，请稍后重试"));}
    @ExceptionHandler(ResponseStatusException.class)public ResponseEntity<?> failure(ResponseStatusException error){int status=error.getStatusCode().value(),code=error instanceof ReservationConflict c?c.code:status==400?40001:status*100;return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(code,error.getReason()));}
}
