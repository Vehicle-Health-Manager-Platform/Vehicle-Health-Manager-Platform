package com.autocare.platform.order;

import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
public class PaymentConfiguration {
    @Bean PaymentChannels paymentChannels(Environment env){return new PaymentChannels(env,Clock.systemUTC());}
    @Bean @ConditionalOnProperty("MYSQL_HOST") PaymentService paymentService(ReservationStore db,ReservationExpiry expiry,PaymentChannels channels){return new PaymentService(db,expiry,channels);}
    @Bean @ConditionalOnProperty("MYSQL_HOST") PaymentRecovery paymentRecovery(PaymentService payments,@org.springframework.beans.factory.annotation.Value("${PAYMENT_QUERY_RECOVERY_ENABLED:false}")boolean enabled){return new PaymentRecovery(payments,enabled);}
}
