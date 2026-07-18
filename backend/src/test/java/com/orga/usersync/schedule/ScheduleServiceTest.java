package com.orga.usersync.schedule;

import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncResult;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ScheduleServiceTest {

    static ScheduledJob job(long id) {
        ScheduledJob j = new ScheduledJob();
        j.setId(id); j.setType(ScheduleType.KEYCLOAK); j.setSourceConnId(1L); j.setTargetConnId(2L);
        j.setMode(SyncMode.CREATE_UPDATE); j.setCron("0 0 2 * * ?"); j.setEnabled(true);
        return j;
    }

    @Test void guard_skips_overlapping_run() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Object gate = new Object();
        boolean[] release = { false };
        SyncDispatcher dispatcher = new SyncDispatcher(null, null) {
            @Override public SyncResult run(ScheduledJob j) {
                calls.incrementAndGet();
                synchronized (gate) { while (!release[0]) { try { gate.wait(50); } catch (InterruptedException e) { break; } } }
                return new SyncResult(0,0,0,0,List.of());
            }
        };
        ScheduleService svc = new ScheduleService(mock(ScheduledJobSink.class), dispatcher, mock(TaskScheduler.class));

        ScheduledJob j = job(1);
        Thread t1 = new Thread(() -> svc.executeGuarded(j));
        t1.start();
        Thread.sleep(30);
        svc.executeGuarded(j);
        assertEquals(1, calls.get());
        synchronized (gate) { release[0] = true; gate.notifyAll(); }
        t1.join(1000);
    }
}
