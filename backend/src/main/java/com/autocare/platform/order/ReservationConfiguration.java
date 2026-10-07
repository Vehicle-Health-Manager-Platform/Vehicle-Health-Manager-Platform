package com.autocare.platform.order;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
@Configuration
@ConditionalOnProperty("MYSQL_HOST")
@EnableScheduling
public class ReservationConfiguration {
    @Bean ReservationStore reservationStore(JdbcTemplate jdbc,ObjectMapper mapper,WriteIntegrityService writes,PlatformTransactionManager manager){return new ReservationStore(jdbc,mapper,writes,Clock.systemUTC(),manager);}
    @Bean ReservationExpiry reservationExpiry(ReservationStore db){return new ReservationExpiry(db);}
    @Bean ReservationSlots reservationSlots(ReservationStore db){return new ReservationSlots(db);}
    @Bean ReservationOrders reservationOrders(ReservationStore db,ReservationExpiry expiry){return new ReservationOrders(db,expiry);}
    @Bean MerchantOrders merchantOrders(ReservationStore db){return new MerchantOrders(db);}
}
