package com.autocare.platform.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.mvc.method.annotation.*;
import org.springframework.web.servlet.view.xslt.XsltViewResolver;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Reachability evidence with real configured controllers, not a patched-library claim. */
class JsonApiViewBoundaryTest extends IsolatedCoreJourneysTest {
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired List<ViewResolver> resolvers;
    @Autowired ReservationStore database;
    @Test void applicationHandlersNeverInferViewsOrStreamViewFragments(){
        assertTrue(resolvers.stream().noneMatch(XsltViewResolver.class::isInstance));
        int handlers=0;
        for(var entry:mappings.getHandlerMethods().entrySet()) {
            var handler=entry.getValue();if(!handler.getBeanType().getName().startsWith("com.autocare."))continue;handlers++;
            assertTrue(AnnotatedElementUtils.hasAnnotation(handler.getBeanType(),RestController.class),handler.toString());
            Class<?> result=handler.getMethod().getReturnType();String generic=handler.getMethod().getGenericReturnType().getTypeName();
            assertFalse(ModelAndView.class.isAssignableFrom(result));assertFalse(View.class.isAssignableFrom(result));
            assertFalse(SseEmitter.class.isAssignableFrom(result));assertFalse(ResponseBodyEmitter.class.isAssignableFrom(result));
            assertFalse(generic.contains("SseEmitter")||generic.contains("FragmentsRendering")||generic.contains("ModelAndView"));
            assertTrue(entry.getKey().getProducesCondition().getProducibleMediaTypes().stream().noneMatch(m->m.toString().startsWith("text/event-stream")));
        }
        assertNotNull(database.jdbc.getDataSource());assertTrue(handlers>0);
        System.out.println("JSON API handler boundary inspected="+handlers);
    }
}
