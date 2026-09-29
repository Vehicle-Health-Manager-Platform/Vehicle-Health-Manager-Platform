package com.autocare.platform.vehicle;

import com.autocare.platform.common.ApiResponse;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("local")
@RequestMapping("/api/demo/vehicles")
public class LocalVehicleController {
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        long ownerId = switch ((int) id) {
            case 1001 -> 1001;
            case 2001 -> 2001;
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "车辆不存在");
        };
        if (!"OWNER".equals(jwt.getClaimAsString("role")) || !Long.toString(ownerId).equals(jwt.getSubject())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该车辆");
        }
        return ApiResponse.success(Map.of("id", id, "owner_id", ownerId, "label", "本地演示车辆"));
    }
}
