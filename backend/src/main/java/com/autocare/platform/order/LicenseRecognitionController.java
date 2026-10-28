package com.autocare.platform.order;
import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.vehicle.VehicleOwner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class LicenseRecognitionController {
    private final ObjectProvider<LicenseRecognition> services;
    public LicenseRecognitionController(ObjectProvider<LicenseRecognition> services){this.services=services;}
    @PostMapping(value="/api/vehicle/license-recognition",consumes="application/json")
    public ResponseEntity<?> recognize(@AuthenticationPrincipal Jwt jwt,@RequestBody String raw,jakarta.servlet.http.HttpServletRequest request){
        var owner=VehicleOwner.from(jwt);if(request.getQueryString()!=null)throw LicenseRecognition.invalid();var input=LicenseRecognition.input(raw);
        var service=services.getIfAvailable();if(service==null)throw LicenseRecognition.unavailable();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.recognize(owner,input)));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> failure(ResponseStatusException e){int status=e.getStatusCode().value();return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(status==400?40001:status*100,e.getReason()));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<?> failure(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"行驶证识别暂不可用，请手动填写"));}
}
