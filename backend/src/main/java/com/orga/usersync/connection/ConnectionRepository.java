package com.orga.usersync.connection;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ConnectionRepository extends JpaRepository<Connection, Long>, ConnectionRepositoryLike {
    Optional<Connection> findByName(String name);
}
