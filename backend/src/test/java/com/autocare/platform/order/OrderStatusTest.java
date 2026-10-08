package com.autocare.platform.order;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 矩阵是本项目唯一的履约规则来源，因此这里用一份独立的期望表对 8×8 组合穷举，
 * 而不复用被测实现自己的常量，避免"用实现验证实现"。
 */
class OrderStatusTest {
    private static final Map<String,Set<String>> EXPECTED=Map.of(
        OrderStatus.PENDING_PAYMENT,Set.of(),
        OrderStatus.PAID,Set.of(OrderStatus.PENDING_PAYMENT),
        OrderStatus.RECEIVED,Set.of(OrderStatus.PAID),
        OrderStatus.IN_SERVICE,Set.of(OrderStatus.RECEIVED),
        OrderStatus.PENDING_VERIFY,Set.of(OrderStatus.IN_SERVICE),
        OrderStatus.COMPLETED,Set.of(OrderStatus.PENDING_VERIFY),
        OrderStatus.CLOSED,Set.of(OrderStatus.PENDING_PAYMENT,OrderStatus.PAID,OrderStatus.RECEIVED,OrderStatus.IN_SERVICE,OrderStatus.PENDING_VERIFY),
        OrderStatus.DISPUTED,Set.of(OrderStatus.PAID,OrderStatus.RECEIVED,OrderStatus.IN_SERVICE,OrderStatus.PENDING_VERIFY));

    @Test void declaresExactlyEightStates(){
        assertEquals(8,OrderStatus.ALL.size());
        assertEquals(EXPECTED.keySet(),OrderStatus.ALL);
        for(String status:OrderStatus.ALL)assertTrue(OrderStatus.known(status));
    }

    @Test void everyPairMatchesTheExpectedMatrix(){
        for(String to:OrderStatus.ALL)for(String from:OrderStatus.ALL)
            assertEquals(EXPECTED.get(to).contains(from),OrderStatus.can(from,to),"from="+from+" to="+to);
    }

    @Test void refusesSelfTransitionsAndUnknownValues(){
        for(String status:OrderStatus.ALL)assertFalse(OrderStatus.can(status,status));
        assertFalse(OrderStatus.can(null,OrderStatus.PAID));
        assertFalse(OrderStatus.can(OrderStatus.PAID,null));
        assertFalse(OrderStatus.known("Cancelled"));
        assertFalse(OrderStatus.known(null));
    }

    @Test void neverReachesTerminalStatesAgain(){
        for(String from:OrderStatus.ALL){
            assertFalse(OrderStatus.can(from,OrderStatus.COMPLETED)&&!from.equals(OrderStatus.PENDING_VERIFY));
            assertFalse(OrderStatus.can(OrderStatus.COMPLETED,OrderStatus.PENDING_PAYMENT));
            assertFalse(OrderStatus.can(OrderStatus.CLOSED,OrderStatus.PAID));
            assertFalse(OrderStatus.can(OrderStatus.CLOSED,OrderStatus.RECEIVED));
        }
        // Paired with the merchant guard, this is D5: only a dispute may be undone.
        assertFalse(OrderStatus.can(OrderStatus.DISPUTED,OrderStatus.COMPLETED));
    }

    @Test void merchantActionsMapToOneTargetWithStableOrder(){
        assertEquals(OrderStatus.RECEIVED,OrderStatus.target(OrderStatus.RECEIVE));
        assertEquals(OrderStatus.IN_SERVICE,OrderStatus.target(OrderStatus.START_SERVICE));
        assertEquals(OrderStatus.PENDING_VERIFY,OrderStatus.target(OrderStatus.FINISH_SERVICE));
        assertEquals(OrderStatus.COMPLETED,OrderStatus.target(OrderStatus.COMPLETE));
        assertEquals("ORDER_CHECK_IN",OrderStatus.audit(OrderStatus.RECEIVE));
        assertEquals("ORDER_SERVICE_START",OrderStatus.audit(OrderStatus.START_SERVICE));
        assertEquals("ORDER_SERVICE_FINISH",OrderStatus.audit(OrderStatus.FINISH_SERVICE));
        assertEquals("ORDER_COMPLETE",OrderStatus.audit(OrderStatus.COMPLETE));
        assertTrue(OrderStatus.action(OrderStatus.RECEIVE));
        assertFalse(OrderStatus.action("PAY"));
        assertFalse(OrderStatus.action("ORDER_CHECK_IN"));
        assertNull(OrderStatus.target("NOPE"));
        assertNull(OrderStatus.audit("NOPE"));
    }

    @Test void actionsFollowTheFulfillmentSequenceAndAgreeWithTheMatrix(){
        assertEquals(List.of(),OrderStatus.actions(OrderStatus.PAID));
        assertEquals(List.of(),OrderStatus.actions(OrderStatus.RECEIVED));
        assertEquals(List.of(),OrderStatus.actions(OrderStatus.IN_SERVICE));
        assertEquals(List.of(OrderStatus.COMPLETE),OrderStatus.actions(OrderStatus.PENDING_VERIFY));
        assertEquals(List.of(),OrderStatus.actions(OrderStatus.PENDING_PAYMENT));
        assertEquals(List.of(),OrderStatus.actions(OrderStatus.COMPLETED));
        assertEquals(List.of(),OrderStatus.actions(OrderStatus.CLOSED));
        assertEquals(List.of(),OrderStatus.actions(OrderStatus.DISPUTED));
        assertEquals(List.of(),OrderStatus.actions(null));
        for(String status:OrderStatus.ALL)for(String action:OrderStatus.actions(status))
            assertTrue(OrderStatus.can(status,OrderStatus.target(action)),status+" -> "+action);
    }

    @Test void noMerchantActionTargetsClosedOrDisputed(){
        for(String status:OrderStatus.ALL)for(String action:OrderStatus.actions(status)){
            assertNotEquals(OrderStatus.CLOSED,OrderStatus.target(action));
            assertNotEquals(OrderStatus.DISPUTED,OrderStatus.target(action));
            assertNotEquals(OrderStatus.PAID,OrderStatus.target(action));
        }
    }

    @Test void onlyADisputeCanBeResumedAndOnlyToItsPreviousState(){
        for(String to:OrderStatus.ALL)for(String from:OrderStatus.ALL)
            assertEquals(OrderStatus.DISPUTED.equals(from)&&EXPECTED.get(OrderStatus.DISPUTED).contains(to),OrderStatus.canResume(from,to),"from="+from+" to="+to);
        // 恢复到不了终态、待支付和关闭，也不能原地不动。
        for(String target:List.of(OrderStatus.COMPLETED,OrderStatus.CLOSED,OrderStatus.PENDING_PAYMENT,OrderStatus.DISPUTED))
            assertFalse(OrderStatus.canResume(OrderStatus.DISPUTED,target),target);
        assertFalse(OrderStatus.canResume(null,OrderStatus.RECEIVED));
        assertFalse(OrderStatus.canResume(OrderStatus.DISPUTED,null));
        // 恢复不是商家动作：矩阵与动作列表都不因此放宽。
        assertEquals(List.of(),OrderStatus.actions(OrderStatus.DISPUTED));
        assertFalse(OrderStatus.can(OrderStatus.DISPUTED,OrderStatus.RECEIVED));
        assertTrue(OrderStatus.canResume(OrderStatus.DISPUTED,OrderStatus.RECEIVED));
        assertEquals("ORDER_DISPUTE_RESOLVE",OrderStatus.DISPUTE_RESOLVE);
        assertFalse(OrderStatus.action(OrderStatus.DISPUTE_RESOLVE));
    }
}
