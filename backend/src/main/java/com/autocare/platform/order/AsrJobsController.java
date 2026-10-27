package com.autocare.platform.order;
import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.vehicle.VehicleOwner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/recognition/asr/jobs")
public class AsrJobsController {
    private final ObjectProvider<AsrJobs> jobs;
    public AsrJobsController(ObjectProvider<AsrJobs> jobs){this.jobs=jobs;}
    private AsrJobs ready(jakarta.servlet.http.HttpServletRequest request){if(request.getQueryString()!=null)throw AsrJobs.bad();var service=jobs.getIfAvailable();if(service==null)throw AsrJobs.unavailable();return service;}
    private ResponseEntity<?> ok(Object body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
    @PostMapping(consumes="application/json") public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,@RequestBody String raw,@RequestHeader(value="Idempotency-Key",required=false)String key,jakarta.servlet.http.HttpServletRequest request){var owner=VehicleOwner.from(jwt);var body=AsrJobs.input(raw);return ok(ready(request).create(owner,body,key));}
    @GetMapping("/{id}") public ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,jakarta.servlet.http.HttpServletRequest request){var owner=VehicleOwner.from(jwt);return ok(ApiResponse.success(ready(request).get(owner,id)));}
    @PostMapping(value="/{id}/cancel",consumes="application/json") public ResponseEntity<?> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestBody String raw,@RequestHeader(value="Idempotency-Key",required=false)String key,jakarta.servlet.http.HttpServletRequest request){var owner=VehicleOwner.from(jwt);AsrJobs.cancelInput(raw);return ok(ready(request).cancel(owner,id,key));}
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> failure(ResponseStatusException e){int status=e.getStatusCode().value();return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(status==400?40001:status*100,e.getReason()));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) public ResponseEntity<?> failure(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"转写任务暂不可用"));}
}
