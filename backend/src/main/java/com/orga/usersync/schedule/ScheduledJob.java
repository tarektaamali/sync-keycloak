package com.orga.usersync.schedule;

import com.orga.usersync.model.SyncMode;
import jakarta.persistence.*;

@Entity
@Table(name = "scheduled_job")
public class ScheduledJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String name;
    @Enumerated(EnumType.STRING) private ScheduleType type;
    private Long sourceConnId;
    private Long targetConnId;
    @Enumerated(EnumType.STRING) private SyncMode mode;
    private boolean includeRoles;
    private String cron;
    private boolean enabled;

    public Long getId() { return id; } public void setId(Long v) { this.id = v; }
    public String getName() { return name; } public void setName(String v) { this.name = v; }
    public ScheduleType getType() { return type; } public void setType(ScheduleType v) { this.type = v; }
    public Long getSourceConnId() { return sourceConnId; } public void setSourceConnId(Long v) { this.sourceConnId = v; }
    public Long getTargetConnId() { return targetConnId; } public void setTargetConnId(Long v) { this.targetConnId = v; }
    public SyncMode getMode() { return mode; } public void setMode(SyncMode v) { this.mode = v; }
    public boolean isIncludeRoles() { return includeRoles; } public void setIncludeRoles(boolean v) { this.includeRoles = v; }
    public String getCron() { return cron; } public void setCron(String v) { this.cron = v; }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { this.enabled = v; }
}
