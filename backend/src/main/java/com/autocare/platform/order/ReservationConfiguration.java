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
    @Bean PointLedger pointLedger(ReservationStore db,PointTaskPolicies policies,@org.springframework.beans.factory.annotation.Value("${WECHAT_APP_ID:}")String app,@org.springframework.beans.factory.annotation.Value("${POINTS_EARNING_ENABLED:false}")boolean enabled,@org.springframework.beans.factory.annotation.Value("${POINTS_AI_VERIFIED_ENABLED:false}")boolean aiEnabled,com.autocare.platform.ai.DeepSeekClient ai){return new PointLedger(db,policies,app,enabled,aiEnabled,ai::officialEndpoint);}

    @Bean PointTaskPolicies pointTaskPolicies(ReservationStore db,com.autocare.platform.gateway.identity.OperatorIdentity operators){return new PointTaskPolicies(db,operators);}
    @Bean PricePolicies pricePolicies(ReservationStore db,com.autocare.platform.gateway.identity.OperatorIdentity operators){return new PricePolicies(db,operators);}
    @Bean ExperienceCurations experienceCurations(ReservationStore db,com.autocare.platform.gateway.identity.OperatorIdentity operators,ExperienceModeration experiences){return new ExperienceCurations(db,operators,experiences);}
    @Bean PriceComparisons priceComparisons(ReservationStore db,PricePolicies policies,com.autocare.platform.gateway.identity.OperatorIdentity operators){return new PriceComparisons(db,policies,operators);}
    @Bean AssessmentPolicies assessmentPolicies(ReservationStore db,com.autocare.platform.gateway.identity.OperatorIdentity operators){return new AssessmentPolicies(db,operators);}
    @Bean PointExchangeCatalog pointExchangeCatalog(ReservationStore db,CouponInventory coupons,com.autocare.platform.gateway.identity.OperatorIdentity operators){return new PointExchangeCatalog(db,coupons,operators);}
    @Bean PointExchanges pointExchanges(ReservationStore db,PointExchangeCatalog catalog,CouponInventory coupons,PointLedger ledger,@org.springframework.beans.factory.annotation.Value("${POINTS_EXCHANGE_ENABLED:false}")boolean enabled){return new PointExchanges(db,catalog,coupons,ledger,enabled);}
    @Bean PointExchangeOffers pointExchangeOffers(ReservationStore db,PointExchangeCatalog catalog,PointLedger ledger,@org.springframework.beans.factory.annotation.Value("${POINTS_EXCHANGE_ENABLED:false}")boolean enabled){return new PointExchangeOffers(db,catalog,ledger,enabled);}
    @Bean PublicCommunityQuestions publicCommunityQuestions(ReservationStore db,com.autocare.platform.gateway.identity.OperatorIdentity operators,CommunityQuestionPolicies policies,ExperienceModeration experiences,@org.springframework.beans.factory.annotation.Value("${COMMUNITY_PUBLICATION_ENABLED:false}")boolean enabled,@org.springframework.beans.factory.annotation.Value("${WECHAT_APP_ID:}")String app){return new PublicCommunityQuestions(db,operators,policies,experiences,enabled,app);}
    @Bean CommunityQuestionPolicies communityQuestionPolicies(ReservationStore db,com.autocare.platform.gateway.identity.OperatorIdentity operators){return new CommunityQuestionPolicies(db,operators);}
    @Bean CommunityQuestions communityQuestions(ReservationStore db,CommunityQuestionPolicies policies,PointLedger identity){return new CommunityQuestions(db,policies,identity);}
    @Bean DiagnosisServicePolicies diagnosisServicePolicies(ReservationStore db,com.autocare.platform.gateway.identity.OperatorIdentity operators){return new DiagnosisServicePolicies(db,operators);}
    @Bean DiagnosisNotifications diagnosisNotifications(ReservationStore db,DiagnosisServicePolicies policies){return new DiagnosisNotifications(db,policies);}
    @Bean DiagnosisCases diagnosisCases(ReservationStore db,DiagnosisServicePolicies policies,InviteDiagnosisPolicies eligibility){return new DiagnosisCases(db,policies,eligibility);}
    @Bean ServiceNotifications serviceNotifications(ReservationStore db,@org.springframework.beans.factory.annotation.Value("${WECHAT_APP_ID:}")String appId){return new ServiceNotifications(db,appId);}
    @Bean ServiceTaskScope serviceTaskScope(ReservationStore db,TechnicianAssignments assignments,@org.springframework.beans.factory.annotation.Value("${WECHAT_APP_ID:}")String appId){return new ServiceTaskScope(db,assignments,appId);}
    @Bean ServiceWorkbench serviceWorkbench(ServiceTaskScope scope){return new ServiceWorkbench(scope);}
    @Bean CustomerRelations customerRelations(ServiceTaskScope scope){return new CustomerRelations(scope);}
    @Bean OrderArchiveGrants orderArchiveGrants(ServiceTaskScope scope){return new OrderArchiveGrants(scope);}
    @Bean OwnerServiceEvidence ownerServiceEvidence(ReservationStore db){return new OwnerServiceEvidence(db);}
    @Bean InviteDiagnosisPolicies inviteDiagnosisPolicies(ReservationStore db,com.autocare.platform.gateway.identity.OperatorIdentity operators){return new InviteDiagnosisPolicies(db,operators);}
    @Bean InviteRewardPolicies inviteRewardPolicies(ReservationStore db,com.autocare.platform.gateway.identity.OperatorIdentity operators,CouponInventory coupons){return new InviteRewardPolicies(db,operators,coupons);}
    @Bean InvitationRewards invitationRewards(ReservationStore db,CouponInventory coupons,@org.springframework.beans.factory.annotation.Value("${INVITE_REWARD_ENABLED:false}")boolean enabled){return new InvitationRewards(db,coupons,enabled);}
    @Bean InvitationRegistration invitationRegistration(ReservationStore db,InvitationRewards rewards){return new InvitationRegistration(db,rewards);}
    @Bean Invitations invitations(ReservationStore db,@org.springframework.beans.factory.annotation.Value("${INVITE_VERIFIED_IDENTITY_ENABLED:false}")boolean verified){return new Invitations(db,verified);}
    @Bean CouponInventory couponInventory(ReservationStore db,com.autocare.platform.gateway.identity.OperatorIdentity operators){return new CouponInventory(db,operators);}
    @Bean CouponTransactions couponTransactions(ReservationStore db){return new CouponTransactions(db);}
    @Bean CouponServiceRedemption couponServiceRedemption(ReservationStore db){return new CouponServiceRedemption(db);}
    @Bean CouponRecovery couponRecovery(ReservationStore db){return new CouponRecovery(db);}
    @Bean BusinessDashboard businessDashboard(ReservationStore db,com.autocare.platform.gateway.identity.OperatorIdentity operators,PlatformTransactionManager manager){return new BusinessDashboard(db,operators,manager);}
    @Bean AssessmentViews assessmentViews(ReservationStore db,AssessmentSnapshots snapshots,com.autocare.platform.gateway.identity.OperatorIdentity operators){return new AssessmentViews(db,snapshots,operators);}
    @Bean AssessmentSnapshots assessmentSnapshots(ReservationStore db,com.autocare.platform.gateway.identity.OperatorIdentity operators,AssessmentPolicies policies,PlatformTransactionManager manager){return new AssessmentSnapshots(db,operators,policies,manager);}
    @Bean ReservationStore reservationStore(JdbcTemplate jdbc,ObjectMapper mapper,WriteIntegrityService writes,PlatformTransactionManager manager){return new ReservationStore(jdbc,mapper,writes,Clock.systemUTC(),manager);}
    @Bean ReservationExpiry reservationExpiry(ReservationStore db){return new ReservationExpiry(db);}
    @Bean ReservationSlots reservationSlots(ReservationStore db){return new ReservationSlots(db);}
    @Bean ReservationOrders reservationOrders(ReservationStore db,ReservationExpiry expiry){return new ReservationOrders(db,expiry);}
    @Bean PlanBookingContext planBookingContext(ReservationStore db,ReservationOrders orders,ReservationSlots slots,VehiclePlanEvidence evidence){return new PlanBookingContext(db,orders,slots,evidence);}
    @Bean MerchantOrders merchantOrders(ReservationStore db){return new MerchantOrders(db);}
    @Bean OrderFulfillment orderFulfillment(ReservationStore db,MerchantOrders orders){return new OrderFulfillment(db,orders);}
    @Bean PickupInspection pickupInspection(ReservationStore db){return new PickupInspection(db);}
    @Bean TechnicianAssignments technicianAssignments(ReservationStore db,@org.springframework.beans.factory.annotation.Value("${WECHAT_APP_ID:}")String appId){return new TechnicianAssignments(db,appId);}
    @Bean OrderDisputes orderDisputes(ReservationStore db){return new OrderDisputes(db);}
    @Bean OrderReviews orderReviews(ReservationStore db){return new OrderReviews(db);}
    @Bean PublicRatings publicRatings(ReservationStore db){return new PublicRatings(db);}
    @Bean ServiceSearch serviceSearch(ReservationStore db){return new ServiceSearch(db);}
    @Bean VehiclePlanEvidence vehiclePlanEvidence(ReservationStore db,com.autocare.platform.gateway.identity.OperatorIdentity operators,com.autocare.platform.vehicle.HealthObservationService observations,ExperienceModeration experiences){return new VehiclePlanEvidence(db,operators,observations,experiences);}
    @Bean HealthPolicies healthPolicies(ReservationStore db,com.autocare.platform.gateway.identity.OperatorIdentity operators){return new HealthPolicies(db,operators);}
    @Bean HealthDashboard healthDashboard(ReservationStore db,HealthPolicies policies,com.autocare.platform.vehicle.HealthObservationService observations,VehiclePlanEvidence evidence,com.autocare.platform.vehicle.ArchiveReminderService targets){return new HealthDashboard(db,policies,observations,evidence,targets);}
    @Bean AiServicePlans aiServicePlans(ReservationStore db,com.autocare.platform.ai.DeepSeekClient client,@org.springframework.beans.factory.annotation.Value("${AI_SERVICE_PLAN_ENABLED:false}")boolean enabled){return new AiServicePlans(db,client,enabled);}
    @Bean ReviewSummaries reviewSummaries(ReservationStore db,com.autocare.platform.ai.DeepSeekClient client,@org.springframework.beans.factory.annotation.Value("${REVIEW_SUMMARY_ENABLED:false}")boolean summary,@org.springframework.beans.factory.annotation.Value("${REVIEW_TEXT_PUBLICATION_ENABLED:false}")boolean text){return new ReviewSummaries(db,client,summary,text);}
    @Bean PublicReviewTexts publicReviewTexts(ReservationStore db,com.autocare.platform.gateway.identity.OperatorIdentity operators,@org.springframework.beans.factory.annotation.Value("${REVIEW_TEXT_PUBLICATION_ENABLED:false}")boolean enabled){return new PublicReviewTexts(db,operators,enabled);}
    @Bean ServiceArchiveJobs serviceArchiveJobs(ReservationStore db,@org.springframework.beans.factory.annotation.Value("${SERVICE_ARCHIVE_ENABLED:false}")boolean enabled,@org.springframework.beans.factory.annotation.Value("${EXPERIENCE_CARD_ENABLED:false}")boolean cardsEnabled){return new ServiceArchiveJobs(db,enabled,cardsEnabled);}
    @Bean ExperienceCards experienceCards(ReservationStore db){return new ExperienceCards(db);}
    @Bean ExperienceModeration experienceModeration(ReservationStore db,com.autocare.platform.gateway.identity.OperatorIdentity operators,@org.springframework.beans.factory.annotation.Value("${EXPERIENCE_PUBLICATION_ENABLED:false}")boolean enabled){return new ExperienceModeration(db,operators,enabled);}
    @Bean MerchantOnboarding merchantOnboarding(ReservationStore db,com.autocare.platform.gateway.identity.OperatorIdentity operators,com.autocare.platform.gateway.identity.AuthRateLimiter limits,@org.springframework.beans.factory.annotation.Value("${MERCHANT_ONBOARDING_ENABLED:false}")String enabled){return new MerchantOnboarding(db,operators,limits,MerchantOnboardingInput.enabled(enabled));}
    @Bean OrderRedemption orderRedemption(ReservationStore db,ServiceWork work,PaymentChannels channels){return new OrderRedemption(db,work,channels);}
    @Bean ServiceWork serviceWork(ReservationStore db,TechnicianAssignments assignments,@org.springframework.beans.factory.annotation.Value("${WECHAT_APP_ID:}")String appId){return new ServiceWork(db,assignments,appId);}
}
