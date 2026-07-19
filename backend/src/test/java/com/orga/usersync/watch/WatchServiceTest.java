package com.orga.usersync.watch;

import com.orga.usersync.model.SyncResult;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class WatchServiceTest {

    static UserWatch watch(long id) {
        UserWatch w = new UserWatch();
        w.setId(id); w.setName("w"); w.setType(WatchType.KEYCLOAK);
        w.setRunMode(RunMode.REPORT_ONLY); w.setCron("0 0 2 * * ?"); w.setEnabled(true);
        return w;
    }

    @Test void guard_skips_overlapping_run() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Object gate = new Object();
        boolean[] release = { false };
        ReconcileService reconcile = mock(ReconcileService.class);
        when(reconcile.reconcile(any(), anyString())).thenAnswer(inv -> {
            calls.incrementAndGet();
            synchronized (gate) { while (!release[0]) { try { gate.wait(50); } catch (InterruptedException e) { break; } } }
            return new SyncResult(0, 0, 0, 0, 0, List.of());
        });
        WatchService svc = new WatchService(mock(UserWatchSink.class), mock(WatchMemberSink.class),
            reconcile, mock(KeycloakReconcileGateway.class), mock(com.orga.usersync.connection.ConnectionService.class),
            mock(TaskScheduler.class));

        UserWatch w = watch(1);
        Thread t1 = new Thread(() -> svc.executeGuarded(w));
        t1.start();
        Thread.sleep(30);
        svc.executeGuarded(w);            // second concurrent tick must be skipped
        assertEquals(1, calls.get());
        synchronized (gate) { release[0] = true; gate.notifyAll(); }
        t1.join(1000);
    }
}
