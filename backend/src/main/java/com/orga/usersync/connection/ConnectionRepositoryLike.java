package com.orga.usersync.connection;

import java.util.List;
import java.util.Optional;

/** Narrow seam so ConnectionService is unit-testable without Spring Data. */
public interface ConnectionRepositoryLike {
    Connection save(Connection c);
    Optional<Connection> findById(Long id);
    List<Connection> findAll();
    void deleteById(Long id);
}
