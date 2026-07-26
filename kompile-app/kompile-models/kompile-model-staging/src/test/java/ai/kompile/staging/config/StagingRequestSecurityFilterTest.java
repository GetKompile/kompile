package ai.kompile.staging.config;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StagingRequestSecurityFilterTest {

    @Test
    void allowsLoopbackReadsButRejectsDnsRebindingHosts() throws Exception {
        MockMvc mvc = mvc(loopback());

        mvc.perform(get("/api/test").with(server("127.0.0.1", 8090)))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));

        mvc.perform(get("/api/test").with(server("attacker.example", 8090)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(
                        "{\"error\":\"Loopback staging rejected an untrusted Host header\"}"));
    }

    @Test
    void everyMutationRequiresNonSimpleRequestHeader() throws Exception {
        MockMvc mvc = mvc(loopback());

        mvc.perform(post("/api/test").with(server("localhost", 8090)))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/test")
                        .with(server("localhost", 8090))
                        .header(StagingRequestSecurityFilter.REQUEST_HEADER, "1"))
                .andExpect(status().isOk())
                .andExpect(content().string("changed"));
    }

    @Test
    void rejectsUnlistedAndCrossSiteBrowserOrigins() throws Exception {
        StagingSecurityProperties properties = loopback();
        properties.setAllowedOrigins(List.of("http://localhost:4200"));
        properties.afterPropertiesSet();
        MockMvc mvc = mvc(properties);

        mvc.perform(get("/api/test")
                        .with(server("localhost", 8090))
                        .header("Origin", "http://attacker.example"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/test")
                        .with(server("localhost", 8090))
                        .header("Origin", "http://localhost:4200")
                        .header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/test")
                        .with(server("localhost", 8090))
                        .header("Origin", "http://localhost:4200")
                        .header("Sec-Fetch-Site", "same-site"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Access-Control-Allow-Origin", "http://localhost:4200"))
                .andExpect(header().doesNotExist(
                        "Access-Control-Allow-Credentials"));
    }

    @Test
    void lanModeRequiresPairingTokenOnReadsAndOpenAiSurface() throws Exception {
        String token = "0123456789abcdef".repeat(2);
        MockMvc mvc = mvc(lan(token));

        mvc.perform(get("/api/test")
                        .with(server("192.168.1.20", 8090))
                        .header("Origin", "http://192.168.1.20:8090"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        StagingRequestSecurityFilter.PAIRING_REQUIRED_HEADER, "true"));

        mvc.perform(get("/v1/models")
                        .with(server("192.168.1.20", 8090))
                        .header("Origin", "http://192.168.1.20:8090")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/test")
                        .with(server("192.168.1.20", 8090))
                        .header("Origin", "http://192.168.1.20:8090")
                        .header(StagingRequestSecurityFilter.TOKEN_HEADER, token))
                .andExpect(status().isOk());
    }

    @Test
    void neverAcceptsPairingTokensFromQueryOrCookies() throws Exception {
        MockMvc mvc = mvc(loopback());

        mvc.perform(get("/api/test")
                        .with(server("localhost", 8090))
                        .queryParam("stagingToken", "secret"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/test")
                        .with(server("localhost", 8090))
                        .cookie(new Cookie("kompile_staging_token", "secret")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exactOriginPreflightAdvertisesOnlyExplicitHeadersWithoutCredentials()
            throws Exception {
        StagingSecurityProperties properties = loopback();
        properties.setAllowedOrigins(List.of("http://localhost:4200"));
        properties.afterPropertiesSet();
        MockMvc mvc = mvc(properties);

        mvc.perform(options("/api/test")
                        .with(server("localhost", 8090))
                        .header("Origin", "http://localhost:4200")
                        .header("Sec-Fetch-Site", "same-site")
                        .header("Access-Control-Request-Method", "POST")
                        .header(
                                "Access-Control-Request-Headers",
                                StagingRequestSecurityFilter.REQUEST_HEADER))
                .andExpect(status().isNoContent())
                .andExpect(header().string(
                        "Access-Control-Allow-Origin", "http://localhost:4200"))
                .andExpect(header().string(
                        "Access-Control-Allow-Headers",
                        "Accept, Authorization, Content-Type, "
                                + StagingRequestSecurityFilter.REQUEST_HEADER
                                + ", " + StagingRequestSecurityFilter.TOKEN_HEADER))
                .andExpect(header().doesNotExist(
                        "Access-Control-Allow-Credentials"));
    }

    @Test
    void lanPreflightAuthenticatesTheActualRequestRatherThanTheBrowserProbe()
            throws Exception {
        String token = "0123456789abcdef".repeat(2);
        MockMvc mvc = mvc(lan(token));

        mvc.perform(options("/api/test")
                        .with(server("192.168.1.20", 8090))
                        .header("Origin", "http://192.168.1.20:8090")
                        .header("Sec-Fetch-Site", "same-origin")
                        .header("Access-Control-Request-Method", "POST")
                        .header(
                                "Access-Control-Request-Headers",
                                StagingRequestSecurityFilter.REQUEST_HEADER + ", "
                                        + StagingRequestSecurityFilter.TOKEN_HEADER))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist(
                        StagingRequestSecurityFilter.PAIRING_REQUIRED_HEADER));
    }

    @Test
    void staticUiRequestsRemainOutsideApiFilter() throws Exception {
        MockMvc mvc = mvc(loopback());

        mvc.perform(get("/assets/test").with(server("attacker.example", 8090)))
                .andExpect(status().isOk())
                .andExpect(content().string("asset"));
    }

    private static StagingSecurityProperties loopback() {
        StagingSecurityProperties properties =
                new StagingSecurityProperties("127.0.0.1");
        properties.afterPropertiesSet();
        return properties;
    }

    private static StagingSecurityProperties lan(String token) {
        StagingSecurityProperties properties =
                new StagingSecurityProperties("0.0.0.0");
        properties.setApiToken(token);
        properties.setAllowedOrigins(List.of("http://192.168.1.20:8090"));
        properties.afterPropertiesSet();
        return properties;
    }

    private static MockMvc mvc(StagingSecurityProperties properties) {
        return MockMvcBuilders.standaloneSetup(new TestController())
                .addFilters(new StagingRequestSecurityFilter(properties))
                .build();
    }

    private static RequestPostProcessor server(String name, int port) {
        return request -> {
            request.setScheme("http");
            request.setServerName(name);
            request.setServerPort(port);
            return request;
        };
    }

    @RestController
    private static final class TestController {
        @GetMapping("/api/test")
        String read() {
            return "ok";
        }

        @PostMapping("/api/test")
        String mutate() {
            return "changed";
        }

        @GetMapping("/v1/models")
        String models() {
            return "models";
        }

        @GetMapping("/assets/test")
        String asset() {
            return "asset";
        }
    }
}
