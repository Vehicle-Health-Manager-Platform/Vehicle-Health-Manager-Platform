package com.autocare.platform.order;
import com.autocare.platform.file.SignedObjectStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
@Configuration
@ConditionalOnProperty("MYSQL_HOST")
public class MileageRecognitionConfiguration {
    @Bean MileageRecognition mileageRecognition(ReservationStore db,ObjectProvider<SignedObjectStore> stores,
        @Value("${MILEAGE_OCR_ENABLED:false}")boolean enabled,@Value("${MILEAGE_OCR_URL:}")String url,
        @Value("${MILEAGE_OCR_SERVICE_TOKEN:}")String token,@Value("${MILEAGE_OCR_ALLOW_PRIVATE_HTTP:false}")boolean privateHttp){
        return new MileageRecognition(db,stores.getIfAvailable(),enabled?new MileageOcrClient(url,token,privateHttp):null,enabled);
    }
}
