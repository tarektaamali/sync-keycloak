package com.orga.usersync.bootstrap;

import com.orga.usersync.connection.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DefaultDataSeederTest {
    @Test void seeds_three_defaults_only_when_absent() {
        ConnectionRepository repo = mock(ConnectionRepository.class);
        ConnectionService svc = mock(ConnectionService.class);
        when(repo.findByName(anyString())).thenReturn(Optional.empty());

        new DefaultDataSeeder(repo, svc).seed();

        verify(svc, times(3)).create(any(ConnectionRequest.class));
    }

    @Test void skips_existing() {
        ConnectionRepository repo = mock(ConnectionRepository.class);
        ConnectionService svc = mock(ConnectionService.class);
        when(repo.findByName(anyString())).thenReturn(Optional.of(new Connection()));

        new DefaultDataSeeder(repo, svc).seed();
        verify(svc, never()).create(any());
    }
}
