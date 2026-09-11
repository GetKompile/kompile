package ai.kompile.app.web.controllers;

import ai.kompile.app.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HttpStatusErrorTest {
    @Test
    void intentionalHttpErrorsKeepTheirStatusAndReason() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Errors())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied by operator policy"));
        mvc.perform(get("/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Harness process failed"));
    }

    @RestController
    static class Errors {
        @GetMapping("/denied")
        void denied() { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied by operator policy"); }
        @GetMapping("/unavailable")
        void unavailable() { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Harness process failed"); }
    }
}
