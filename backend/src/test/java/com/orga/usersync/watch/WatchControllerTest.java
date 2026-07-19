package com.orga.usersync.watch;

import com.orga.usersync.config.SecurityConfig;
import com.orga.usersync.model.SyncResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WatchController.class)
@Import(SecurityConfig.class)
class WatchControllerTest {
    @Autowired MockMvc mvc;
    @MockBean WatchService svc;

    @Test void run_now_returns_result_with_disabled_count() throws Exception {
        when(svc.runNow(anyLong())).thenReturn(new SyncResult(0, 0, 0, 0, 2, List.of()));
        mvc.perform(post("/api/watches/9/run").with(jwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.disabled").value(2));
    }
}
