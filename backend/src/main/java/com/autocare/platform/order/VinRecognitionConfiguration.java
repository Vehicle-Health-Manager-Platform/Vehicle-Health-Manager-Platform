package com.autocare.platform.order;

import com.autocare.platform.vehicle.vin.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty("MYSQL_HOST")
public class VinRecognitionConfiguration {
    @Bean VinDecoder configuredVinDecoder(@Value("${VIN_DECODE_ENABLED:false}")boolean enabled,
            @Value("${VIN_DECODER_PROVIDER:unconfigured}")String provider,
            @Value("${VIN_LOCAL_DATA_PATH:}")String path,@Value("${VIN_LOCAL_DATA_SHA256:}")String sha,
            @Value("${VIN_LOCAL_ALLOW_SYNTHETIC:false}")boolean synthetic) {
        if(enabled && "LOCAL_LICENSED".equals(provider)) return new LocalVinDecoder(java.nio.file.Path.of(path),sha,synthetic);
        return vinDecoder(enabled,provider);
    }
    VinDecoder vinDecoder(boolean enabled,
            String provider) {
        if (enabled && !"NHTSA_VPIC".equals(provider)) throw new IllegalArgumentException("VIN解码启用时必须指定NHTSA_VPIC来源");
        return new VpicVinDecoder(enabled);
    }
    @Bean VinRecognition vinRecognition(ReservationStore db,VinDecoder decoder,@Value("${VIN_DECODE_ENABLED:false}")boolean enabled) {
        return new VinRecognition(db,decoder,enabled);
    }
}
