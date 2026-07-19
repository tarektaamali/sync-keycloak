package com.orga.usersync.audit;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sync_run")
public class SyncRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Instant timestamp;
    private String actor;
    private String sourceConn;
    private String targetConn;
    private String mode;
    private boolean includeRoles;
    private int created; private int updated; private int deleted; private int skipped; private int disabled; private int errorCount;
    private String status;

    public Long getId() { return id; }
    public Instant getTimestamp() { return timestamp; } public void setTimestamp(Instant v) { this.timestamp = v; }
    public String getActor() { return actor; } public void setActor(String v) { this.actor = v; }
    public String getSourceConn() { return sourceConn; } public void setSourceConn(String v) { this.sourceConn = v; }
    public String getTargetConn() { return targetConn; } public void setTargetConn(String v) { this.targetConn = v; }
    public String getMode() { return mode; } public void setMode(String v) { this.mode = v; }
    public boolean isIncludeRoles() { return includeRoles; } public void setIncludeRoles(boolean v) { this.includeRoles = v; }
    public int getCreated() { return created; } public void setCreated(int v) { this.created = v; }
    public int getUpdated() { return updated; } public void setUpdated(int v) { this.updated = v; }
    public int getDeleted() { return deleted; } public void setDeleted(int v) { this.deleted = v; }
    public int getSkipped() { return skipped; } public void setSkipped(int v) { this.skipped = v; }
    public int getDisabled() { return disabled; } public void setDisabled(int v) { this.disabled = v; }
    public int getErrorCount() { return errorCount; } public void setErrorCount(int v) { this.errorCount = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
}
