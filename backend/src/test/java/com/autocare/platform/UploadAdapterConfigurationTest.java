package com.autocare.platform;

import com.autocare.platform.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class UploadAdapterConfigurationTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(PrivateUploadConfiguration.class, UploadAdapterConfiguration.class);
    @Test void disabledAdaptersDoNotCreateRealOrTestDependencies() {
        runner.withPropertyValues("UPLOAD_STORAGE_ENABLED=false", "UPLOAD_SCAN_ENABLED=false").run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(VirusScanner.class).isEmpty());
            assertTrue(context.getBeansOfType(PrivateObjectStore.class).isEmpty());
            assertNotNull(context.getBean(PrivateFileAccessService.class));
        });
    }
    @Test void enabledButIncompleteOrPlaceholderConfigurationFailsStartup() {
        for (String[] properties : new String[][]{
            {"UPLOAD_STORAGE_ENABLED=true"}, {"UPLOAD_SCAN_ENABLED=true"},
            {"UPLOAD_SCAN_ENABLED=true", "CLAMAV_HOST=unconfigured"},
            {"UPLOAD_SCAN_ENABLED=true", "CLAMAV_HOST=127.0.0.1", "CLAMAV_SCAN_TIMEOUT_MS=1"}
        }) runner.withPropertyValues(properties).run(context -> assertNotNull(context.getStartupFailure()));
    }
    @Test void localScannerConfigurationIsRealAndDoesNotNeedLiveServiceToStart() {
        runner.withPropertyValues("UPLOAD_SCAN_ENABLED=true", "CLAMAV_HOST=127.0.0.1", "CLAMAV_PORT=3310").run(context -> {
            assertNull(context.getStartupFailure()); assertInstanceOf(ClamdVirusScanner.class, context.getBean(VirusScanner.class));
            assertTrue(context.getBeansOfType(PrivateObjectStore.class).isEmpty());
        });
    }
}
