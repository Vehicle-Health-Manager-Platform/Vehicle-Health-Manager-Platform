package com.autocare.platform.vehicle;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("local")
@RequestMapping("/api/demo/vehicles")
public class LocalMileageController {
    private final ObjectProvider<LocalMileageService> services;
    public LocalMileageController(ObjectProvider<LocalMileageService> services) { this.services = services; }

    @PostMapping("/{id}/mileage")
    public JsonNode update(@PathVariable long id, @AuthenticationPrincipal Jwt jwt,
                           @RequestHeader(value="Idempotency-Key", required=false) String key,
                           @RequestBody JsonNode body) {
        if (jwt == null || !"OWNER".equals(jwt.getClaimAsString("role"))
            || !(jwt.getClaimAsString("subject_type") == null || "user".equals(jwt.getClaimAsString("subject_type")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅车主可修改示例车辆");
        }
        long owner;
        try { owner = Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException exception) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "车主身份无效"); }
        if (owner <= 0 || id <= 0) throw WriteIntegrityService.badRequest("车辆或身份无效");
        WriteIntegrityService.normalizeKey(key);
        JsonNode mileage = body == null ? null : body.get("current_mileage");
        if (body == null || !body.isObject() || body.size() != 1 || mileage == null
            || !mileage.isIntegralNumber() || !mileage.canConvertToInt() || mileage.intValue() < 0) {
            throw WriteIntegrityService.badRequest("正文仅允许非负整数 current_mileage");
        }
        LocalMileageService service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "示例写入数据库尚未配置");
        return service.update(owner, id, key, mileage.intValue());
    }
}
