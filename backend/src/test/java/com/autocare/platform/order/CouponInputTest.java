package com.autocare.platform.order;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class CouponInputTest {
 @Test void canonicalUtcDoesNotNormalizeImpossibleOrLeapTimes(){var json=new ObjectMapper();for(String time:new String[]{"2026-10-14T24:00:00Z","2026-02-30T00:00:00Z","2026-10-14T23:59:60Z","2026-10-14T00:00:00+00:00"})assertThrows(ResponseStatusException.class,()->CouponInput.time(json.getNodeFactory().textNode(time)));assertEquals("2026-10-14T00:00:00Z",CouponInput.time(json.getNodeFactory().textNode("2026-10-14T00:00:00Z")).toString());}
 @Test void missingDatabaseFailsClosed(){var controller=new CouponController(new DefaultListableBeanFactory().getBeanProvider(CouponInventory.class));assertEquals(503,assertThrows(ResponseStatusException.class,controller::service).getStatusCode().value());}
}
