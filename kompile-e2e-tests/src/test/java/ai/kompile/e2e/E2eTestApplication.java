package ai.kompile.e2e;

import ai.kompile.app.MainApplication;
import ai.kompile.staging.ModelStagingApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Lightweight Spring Boot application for E2E tests.
 * Avoids MainApplication's heavy ND4J bootstrap while still
 * scanning all ai.kompile packages for conditional beans.
 *
 * <p>kompile-model-staging is a standalone app, but this module depends on it. Its
 * {@code ai.kompile.staging.web} and {@code ai.kompile.staging.mcp} controllers collide with the
 * app's own web layer (duplicate {@code ServiceEndpointsConfigController} bean, ambiguous
 * {@code GET /} and {@code GET /mcp/status} mappings), so both packages are excluded.
 * {@code MainApplication} and {@code ModelStagingApplication} are excluded too: they are
 * {@code @SpringBootApplication}s themselves, and their own scans would re-register those
 * controllers. This is {@code @SpringBootApplication} decomposed so the single
 * {@code @ComponentScan} keeps its default exclude filters alongside the extra ones.</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "ai.kompile", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                ModelStagingApplication.class,
                MainApplication.class
        }),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = {
                "ai\\.kompile\\.staging\\.web\\..*",
                "ai\\.kompile\\.staging\\.mcp\\..*"
        })
})
public class E2eTestApplication {
}
