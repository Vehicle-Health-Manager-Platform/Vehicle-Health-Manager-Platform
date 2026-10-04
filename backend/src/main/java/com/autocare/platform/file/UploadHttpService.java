package com.autocare.platform.file;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.AuthTokens;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.scheduling.annotation.Scheduled;

public class UploadHttpService {
    private final JdbcUploadRequests requests;
    private final VirusScanner scanner;
    private final PrivateObjectStore store;
    public UploadHttpService(JdbcUploadRequests requests, VirusScanner scanner, PrivateObjectStore store) {
        this.requests=requests;this.scanner=scanner;this.store=store;
    }
    public boolean ready() {return scanner!=null && store!=null;}
    public void admission(UploadOwner owner,boolean upload) {
        if(!ready()) throw UploadHttpException.unavailable();
        requests.admission(owner,upload);
    }
    public JsonNode upload(UploadOwner owner,String key,String filename,InputStream input) {
        key=WriteIntegrityService.normalizeKey(key);
        var file=new FileValidator().validate(filename,input);
        String hash;
        try {hash=AuthTokens.sha256(file.contentType()+":"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.bytes())));}
        catch(Exception e){throw UploadHttpException.unavailable();}
        if(!ready()) throw UploadHttpException.unavailable();
        var reservation=requests.reserve(owner,key,hash);
        if(reservation.replay()!=null) return reservation.replay();
        boolean mayHaveObject=false;
        try {
            if(!store.isPrivate()) throw UploadHttpException.unavailable();
            var scan=scanner.scan(file.bytes().clone());
            if(scan==VirusScanner.Result.INFECTED) throw new UploadHttpException(422,"上传文件未通过病毒检查");
            if(scan!=VirusScanner.Result.CLEAN) throw UploadHttpException.unavailable();
            requests.checkOwner(owner);
            mayHaveObject=true;
            store.put(reservation.key(),file.contentType(),file.bytes());
            return requests.succeed(owner,reservation,file);
        } catch(RuntimeException e) {
            int status=e instanceof UploadHttpException error ? error.status() : 503;
            JsonNode recovered=requests.failed(reservation,status,mayHaveObject);
            if(recovered!=null) {requests.checkOwner(owner);return recovered;}
            if(e instanceof UploadHttpException error) throw error;
            throw UploadHttpException.unavailable();
        }
    }
    @Scheduled(fixedDelayString="${UPLOAD_CLEANUP_INTERVAL_MS:60000}")
    public void reconcile() {
        if(!ready()) return;
        try {
            requests.expireProcessing();
            for(int i=0;i<100;i++) {
                var task=requests.claimCleanup(); if(task==null) break;
                try {
                    if(!requests.safeToDelete(task) || !store.isPrivate()) {requests.retryCleanup(task);continue;}
                    store.delete(task.key());requests.cleaned(task);
                } catch(RuntimeException e) {requests.retryCleanup(task);report();}
            }
        } catch(RuntimeException e) {report();}
    }
    private void report() {
        org.slf4j.LoggerFactory.getLogger(UploadHttpService.class).warn("event=upload_reconciliation_pending");
    }
}
