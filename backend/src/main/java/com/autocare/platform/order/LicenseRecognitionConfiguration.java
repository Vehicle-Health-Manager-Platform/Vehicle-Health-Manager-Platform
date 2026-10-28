package com.autocare.platform.order;
import com.autocare.platform.file.SignedObjectStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty("MYSQL_HOST")
public class LicenseRecognitionConfiguration {
    @Bean LicenseRecognition licenseRecognition(ReservationStore db,ObjectProvider<SignedObjectStore> stores,
            @Value("${LICENSE_OCR_ENABLED:false}")boolean enabled,@Value("${LICENSE_OCR_URL:}")String url,
            @Value("${LICENSE_OCR_SERVICE_TOKEN:}")String token,@Value("${LICENSE_OCR_ALLOW_PRIVATE_HTTP:false}")boolean privateHttp){
        return new LicenseRecognition(db,stores.getIfAvailable(),enabled?MileageOcrClient.forLicense(url,token,privateHttp):null,enabled);
    }
}
