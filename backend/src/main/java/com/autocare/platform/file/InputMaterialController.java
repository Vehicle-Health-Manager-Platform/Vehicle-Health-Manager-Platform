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
@RequestMapping("/api/input-materials/{purpose}/{vehicleId}")
public class InputMaterialController {
    private final ObjectProvider<InputMaterialService> services;
    public InputMaterialController(ObjectProvider<InputMaterialService> services){this.services=services;}
    private InputMaterialService service(){var value=services.getIfAvailable();if(value==null)throw UploadHttpException.unavailable();return value;}
    private UploadOwner owner(HttpServletRequest request){
        if(!(request.getAttribute(UploadAdmissionFilter.OWNER) instanceof UploadOwner owner))throw new UploadHttpException(403,"仅正式车主会话可使用辅助材料");return owner;
    }
    @PostMapping(value="/upload",consumes="multipart/form-data")
    public JsonNode upload(@PathVariable String purpose,@PathVariable long vehicleId,MultipartHttpServletRequest request){
        var scope=new InputMaterialScope(purpose,vehicleId);var actor=owner(request);
        try{
            var files=request.getMultiFileMap();
            if(files.size()!=1 || !files.containsKey("file") || files.get("file").size()!=1 || !request.getParameterMap().isEmpty() || request.getParts().size()!=1)
                throw new UploadHttpException(400,"仅允许一个 file 辅助材料字段");
            var file=files.getFirst("file");
            if(file==null || file.isEmpty())throw new UploadHttpException(400,"上传文件无效");
            if(file.getSize()>InputMaterialValidator.PDF_MAX)throw new UploadHttpException(413,"辅助材料过大");
            String kind=request.getHeader("X-Material-Kind");
            if(!("audio".equals(kind)||"pdf".equals(kind)||"image".equals(kind)))throw new UploadHttpException(400,"辅助材料类型无效");
            // Native temporary filenames are not portable; validate bytes against the explicit kind.
            try(var input=file.getInputStream()){return service().upload(actor,scope,request.getHeader("Idempotency-Key"),"audio".equals(kind)?"material.mp3":"image".equals(kind)?"material.image":"material.pdf",input);}
        }catch(jakarta.servlet.ServletException|IOException e){throw new UploadHttpException(400,"上传请求无效");}
    }
    @GetMapping("/{id}/access")
    public ResponseEntity<ApiResponse<Map<String,Object>>> access(@PathVariable String purpose,@PathVariable long vehicleId,@PathVariable long id,HttpServletRequest request){
        if(!request.getParameterMap().isEmpty() || id<1 || id>9007199254740991L)
            throw new UploadHttpException(400,"辅助材料访问参数无效");
        var value=service().access(owner(request),new InputMaterialScope(purpose,vehicleId),id);
        return ResponseEntity.ok().header("Cache-Control","no-store").body(ApiResponse.success(Map.of("url",value.url(),"expires_at",value.expiresAt())));
    }
}
