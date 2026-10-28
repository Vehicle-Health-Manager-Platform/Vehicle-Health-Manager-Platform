package com.autocare.platform.order;

import com.autocare.platform.vehicle.vin.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty("MYSQL_HOST")
public class VinRecognitionConfiguration {
    @Bean VinDecoder vinDecoder(@Value("${VIN_DECODE_ENABLED:false}")boolean enabled,
            @Value("${VIN_DECODER_PROVIDER:unconfigured}")String provider) {
        if (enabled && !"NHTSA_VPIC".equals(provider)) throw new IllegalArgumentException("VIN解码启用时必须指定NHTSA_VPIC来源");
        return new VpicVinDecoder(enabled);
    }
    @Bean VinRecognition vinRecognition(ReservationStore db,VinDecoder decoder,@Value("${VIN_DECODE_ENABLED:false}")boolean enabled) {
        return new VinRecognition(db,decoder,enabled);
    }
}
