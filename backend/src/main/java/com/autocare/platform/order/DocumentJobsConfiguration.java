package com.autocare.platform.order;
import com.autocare.platform.file.SignedObjectStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
@Configuration @ConditionalOnProperty("MYSQL_HOST")
public class DocumentJobsConfiguration {
    @Bean DocumentJobs documentJobs(ReservationStore db,@Value("${DOCUMENT_ENABLED:false}")boolean enabled){return new DocumentJobs(db,enabled);}
    @Bean DocumentJobWorker documentJobWorker(DocumentJobs jobs,ObjectProvider<SignedObjectStore> stores,@Value("${DOCUMENT_ENABLED:false}")boolean enabled,@Value("${DOCUMENT_URL:}")String url,@Value("${DOCUMENT_SERVICE_TOKEN:}")String token,@Value("${DOCUMENT_ALLOW_PRIVATE_HTTP:false}")boolean privateHttp){return new DocumentJobWorker(jobs,stores.getIfAvailable(),enabled?new DocumentHttpClient(url,token,privateHttp):null,enabled);}
}
