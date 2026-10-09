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

@RestController
public class ExperienceCardsController {
    private final ObjectProvider<ExperienceCards> services;
    public ExperienceCardsController(ObjectProvider<ExperienceCards> services) { this.services = services; }
    private ExperienceCards configured() {
        var s = services.getIfAvailable();
        if (s == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "经验卡片数据库尚未配置"); return s;
    }
    private static ResponseEntity<?> ok(Object value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    @GetMapping("/api/experience-cards")
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String,String> p) {
        var owner = VehicleOwner.from(jwt);
        if (!p.containsKey("vehicle_id") || p.entrySet().stream().anyMatch(e -> !java.util.Set.of("vehicle_id", "page", "page_size").contains(e.getKey()) || e.getValue().size() != 1)) throw ReservationInput.bad();
        try {
            for (var entry : p.entrySet()) if (!entry.getValue().get(0).matches("[1-9][0-9]{0,15}")) throw ReservationInput.bad();
            long vehicle = Long.parseLong(p.getFirst("vehicle_id"));
            int page = p.containsKey("page") ? Integer.parseInt(p.getFirst("page")) : 1;
            int size = p.containsKey("page_size") ? Integer.parseInt(p.getFirst("page_size")) : 20;
            com.autocare.platform.service.ServiceCatalog.validateId(vehicle);
            ExperienceCards.page(page, size);
            return ok(ApiResponse.success(configured().list(owner, vehicle, page, size)));
        } catch (NumberFormatException e) { throw ReservationInput.bad(); }
    }
    private ResponseEntity<?> change(Jwt jwt, long id, String key, String raw, MultiValueMap<String,String> p, boolean consent) {
        var owner = VehicleOwner.from(jwt); if (!p.isEmpty()) throw ReservationInput.bad();
        com.autocare.platform.service.ServiceCatalog.validateId(id);
        com.autocare.platform.common.write.WriteIntegrityService.normalizeKey(key);
        var body = ExperienceCardInput.parse(raw, consent);
        return ok(configured().change(owner, id, key, body, consent));
    }
    @PostMapping(value="/api/experience-cards/{id}/consent", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> consent(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestHeader(value="Idempotency-Key", required=false) String key,
        @RequestBody String raw, @RequestParam MultiValueMap<String,String> p) { return change(jwt,id,key,raw,p,true); }
    @PostMapping(value="/api/experience-cards/{id}/withdraw", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestHeader(value="Idempotency-Key", required=false) String key,
        @RequestBody String raw, @RequestParam MultiValueMap<String,String> p) { return change(jwt,id,key,raw,p,false); }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<?> database() { return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"经验卡片服务暂不可用，请使用原请求重试")); }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> failure(ResponseStatusException e) {
        int s = e.getStatusCode().value(), code = e instanceof FulfillmentConflict c ? c.code : s==400 ? 40001 : s*100;
        return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(code,e.getReason()));
    }
}
