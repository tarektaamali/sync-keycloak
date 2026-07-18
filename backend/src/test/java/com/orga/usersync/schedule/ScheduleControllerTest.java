package com.orga.usersync.schedule;

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

@WebMvcTest(ScheduleController.class)
@Import(SecurityConfig.class)
class ScheduleControllerTest {
    @Autowired MockMvc mvc;
    @MockBean ScheduleService svc;

    @Test void run_now_returns_result() throws Exception {
        when(svc.runNow(anyLong())).thenReturn(new SyncResult(1, 0, 0, 0, List.of()));
        mvc.perform(post("/api/schedules/5/run").with(jwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(1));
    }
}
