package com.autocare.platform.order;
import com.autocare.platform.file.SignedObjectStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
@Configuration @ConditionalOnProperty("MYSQL_HOST")
public class AsrJobsConfiguration {
    @Bean AsrJobs asrJobs(ReservationStore db,@Value("${ASR_ENABLED:false}")boolean enabled){return new AsrJobs(db,enabled);}
    @Bean AsrJobWorker asrJobWorker(AsrJobs jobs,ObjectProvider<SignedObjectStore> stores,@Value("${ASR_ENABLED:false}")boolean enabled,@Value("${ASR_URL:}")String url,@Value("${ASR_SERVICE_TOKEN:}")String token,@Value("${ASR_ALLOW_PRIVATE_HTTP:false}")boolean privateHttp){return new AsrJobWorker(jobs,stores.getIfAvailable(),enabled?new AsrHttpClient(url,token,privateHttp):null,enabled);}
}
