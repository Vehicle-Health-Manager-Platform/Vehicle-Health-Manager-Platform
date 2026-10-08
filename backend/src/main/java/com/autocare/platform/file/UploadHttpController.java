package com.autocare.platform.file;

import com.autocare.platform.common.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequestMapping({"/api/file", "/api/merchant/files", "/api/tech/files"})
public class UploadHttpController {
    private final ObjectProvider<UploadHttpService> services;
    private final PrivateFileAccessService access;
    public UploadHttpController(ObjectProvider<UploadHttpService> services,PrivateFileAccessService access) {
        this.services=services;this.access=access;
    }
    private UploadOwner owner(HttpServletRequest request) {
        if(!(request.getAttribute(UploadAdmissionFilter.OWNER) instanceof UploadOwner owner))
            throw new UploadHttpException(403,"仅正式车主会话可使用图片接口");
        return owner;
    }
    @PostMapping(value="/upload",consumes="multipart/form-data")
    public JsonNode upload(MultipartHttpServletRequest request) {
        var owner=owner(request);
        var files=request.getMultiFileMap();
        try {
            if(files.size()!=1 || !files.containsKey("file") || files.get("file").size()!=1
                || !request.getParameterMap().isEmpty() || request.getParts().size()!=1)
                throw new UploadHttpException(400,"仅允许一个 file 图片字段");
        } catch(jakarta.servlet.ServletException|IOException e) {throw new UploadHttpException(400,"上传请求无效");}
        var file=files.getFirst("file");
        if(file==null || file.isEmpty()) throw new UploadHttpException(400,"上传文件无效");
        if(file.getSize()>FileValidator.MAX_BYTES) throw new UploadHttpException(413,"上传文件过大");
        var service=services.getIfAvailable();if(service==null) throw UploadHttpException.unavailable();
        try(var input=file.getInputStream()) {
            return service.upload(owner,request.getHeader("Idempotency-Key"),file.getOriginalFilename(),input);
        } catch(IOException e) {throw UploadHttpException.unavailable();}
    }
    @GetMapping("/{id}/access")
    public ResponseEntity<ApiResponse<Map<String,Object>>> access(@PathVariable long id,HttpServletRequest request) {
        var owner=owner(request);
        var service=services.getIfAvailable();if(service==null) throw UploadHttpException.unavailable();
        var signed=access.sign(owner.actor(),id);
        return ResponseEntity.ok().header("Cache-Control","no-store")
            .body(ApiResponse.success(Map.of("url",signed.url(),"expires_at",signed.expiresAt())));
    }
}
