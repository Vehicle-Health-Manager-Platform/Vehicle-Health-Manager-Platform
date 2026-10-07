package com.autocare.platform.order;

import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
public class PaymentConfiguration {
    @Bean PaymentChannels paymentChannels(Environment env){return new PaymentChannels(env,Clock.systemUTC());}
    @Bean @ConditionalOnProperty("MYSQL_HOST") PaymentService paymentService(ReservationStore db,ReservationExpiry expiry,PaymentChannels channels){return new PaymentService(db,expiry,channels);}
}
