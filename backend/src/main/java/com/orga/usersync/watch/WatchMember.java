package com.orga.usersync.watch;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "watch_member",
       uniqueConstraints = @UniqueConstraint(columnNames = {"watch_id", "username"}))
public class WatchMember {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long watchId;
    private String username;
    @Enumerated(EnumType.STRING) private WatchMemberState lastState;
    private Instant firstSeen;
    private Instant lastSeen;

    public Long getId() { return id; } public void setId(Long v) { this.id = v; }
    public Long getWatchId() { return watchId; } public void setWatchId(Long v) { this.watchId = v; }
    public String getUsername() { return username; } public void setUsername(String v) { this.username = v; }
    public WatchMemberState getLastState() { return lastState; } public void setLastState(WatchMemberState v) { this.lastState = v; }
    public Instant getFirstSeen() { return firstSeen; } public void setFirstSeen(Instant v) { this.firstSeen = v; }
    public Instant getLastSeen() { return lastSeen; } public void setLastSeen(Instant v) { this.lastSeen = v; }
}
