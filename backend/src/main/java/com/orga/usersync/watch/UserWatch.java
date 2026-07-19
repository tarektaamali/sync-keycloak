package com.orga.usersync.watch;

import jakarta.persistence.*;

@Entity
@Table(name = "user_watch")
public class UserWatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String name;
    @Enumerated(EnumType.STRING) private WatchType type;
    private Long sourceConnId;
    private Long targetConnId;
    @Enumerated(EnumType.STRING) private SelectionMode selectionMode;
    @Column(length = 4000) private String selectionPayload;
    private boolean includeRoles;
    @Enumerated(EnumType.STRING) private OnDeletePolicy onDelete;
    @Enumerated(EnumType.STRING) private RunMode runMode;
    private String cron;
    private boolean enabled;

    public Long getId() { return id; } public void setId(Long v) { this.id = v; }
    public String getName() { return name; } public void setName(String v) { this.name = v; }
    public WatchType getType() { return type; } public void setType(WatchType v) { this.type = v; }
    public Long getSourceConnId() { return sourceConnId; } public void setSourceConnId(Long v) { this.sourceConnId = v; }
    public Long getTargetConnId() { return targetConnId; } public void setTargetConnId(Long v) { this.targetConnId = v; }
    public SelectionMode getSelectionMode() { return selectionMode; } public void setSelectionMode(SelectionMode v) { this.selectionMode = v; }
    public String getSelectionPayload() { return selectionPayload; } public void setSelectionPayload(String v) { this.selectionPayload = v; }
    public boolean isIncludeRoles() { return includeRoles; } public void setIncludeRoles(boolean v) { this.includeRoles = v; }
    public OnDeletePolicy getOnDelete() { return onDelete; } public void setOnDelete(OnDeletePolicy v) { this.onDelete = v; }
    public RunMode getRunMode() { return runMode; } public void setRunMode(RunMode v) { this.runMode = v; }
    public String getCron() { return cron; } public void setCron(String v) { this.cron = v; }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { this.enabled = v; }
}
