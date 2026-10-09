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
    @Bean OrderFulfillment orderFulfillment(ReservationStore db,MerchantOrders orders){return new OrderFulfillment(db,orders);}
    @Bean PickupInspection pickupInspection(ReservationStore db){return new PickupInspection(db);}
    @Bean TechnicianAssignments technicianAssignments(ReservationStore db,@org.springframework.beans.factory.annotation.Value("${WECHAT_APP_ID:}")String appId){return new TechnicianAssignments(db,appId);}
    @Bean OrderDisputes orderDisputes(ReservationStore db){return new OrderDisputes(db);}
    @Bean OrderReviews orderReviews(ReservationStore db){return new OrderReviews(db);}
    @Bean ServiceArchiveJobs serviceArchiveJobs(ReservationStore db,@org.springframework.beans.factory.annotation.Value("${SERVICE_ARCHIVE_ENABLED:false}")boolean enabled,@org.springframework.beans.factory.annotation.Value("${EXPERIENCE_CARD_ENABLED:false}")boolean cardsEnabled){return new ServiceArchiveJobs(db,enabled,cardsEnabled);}
    @Bean ExperienceCards experienceCards(ReservationStore db){return new ExperienceCards(db);}
    @Bean OrderRedemption orderRedemption(ReservationStore db,ServiceWork work,PaymentChannels channels){return new OrderRedemption(db,work,channels);}
    @Bean ServiceWork serviceWork(ReservationStore db,TechnicianAssignments assignments,@org.springframework.beans.factory.annotation.Value("${WECHAT_APP_ID:}")String appId){return new ServiceWork(db,assignments,appId);}
}
