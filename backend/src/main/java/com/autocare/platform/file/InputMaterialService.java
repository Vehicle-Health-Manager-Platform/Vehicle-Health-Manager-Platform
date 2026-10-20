package com.autocare.platform.file;

import java.io.InputStream;
import java.time.Instant;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;

public class InputMaterialService {
    private final UploadHttpService uploads;
    private final JdbcUploadRequests requests;
    private final JdbcTemplate jdbc;
    private final SignedObjectStore store;
    private final boolean enabled;
    public InputMaterialService(UploadHttpService uploads,JdbcUploadRequests requests,JdbcTemplate jdbc,SignedObjectStore store,boolean enabled) {
        this.uploads=uploads;this.requests=requests;this.jdbc=jdbc;this.store=store;this.enabled=enabled;
    }
    private void ready() { if(!enabled || store==null || !uploads.ready()) throw UploadHttpException.unavailable(); }
    public void admission(UploadOwner owner,InputMaterialScope scope,boolean upload) {
        ready(); uploads.admission(owner,upload); requests.checkScope(owner,scope);
    }
    public JsonNode upload(UploadOwner owner,InputMaterialScope scope,String key,String filename,InputStream input) {
        ready();return uploads.uploadMaterial(owner,key,filename,input,scope);
    }
    public PrivateFileAccessService.SignedAccess access(UploadOwner owner,InputMaterialScope scope,long id) {
        ready();requests.checkScope(owner,scope);
        try {
            var rows=jdbc.query("SELECT object_key,content_type,size_bytes FROM input_material WHERE id=? AND owner_id=? AND vehicle_id=? AND purpose=? AND scan_status='CLEAN' AND is_deleted=0",
                (rs,n)->new FileMetadataRepository.Metadata(id,rs.getString(1),rs.getString(2),rs.getLong(3),"CLEAN"),id,owner.id(),scope.vehicleId(),scope.purpose());
            if(rows.size()!=1)throw new UploadHttpException(404,"车辆或辅助材料不可用");
            var file=rows.get(0);
            if(!store.isPrivate())throw UploadHttpException.unavailable();
            var actual=store.stat(file.objectKey());
            if(actual.sizeBytes()!=file.sizeBytes() || !actual.contentType().equals(file.contentType()))throw UploadHttpException.unavailable();
            Instant expires=Instant.now().plusSeconds(60);
            String url=store.signAttachment(file.objectKey(),60,file.contentType());
            requests.checkScope(owner,scope);
            // Recheck soft deletion as well as identity after external signing.
            if(jdbc.queryForObject("SELECT COUNT(*) FROM input_material WHERE id=? AND is_deleted=0 AND scan_status='CLEAN'",Integer.class,id)!=1)
                throw new UploadHttpException(404,"车辆或辅助材料不可用");
            return new PrivateFileAccessService.SignedAccess(url,expires);
        }catch(UploadHttpException e){throw e;}catch(RuntimeException e){throw UploadHttpException.unavailable();}
    }
}
