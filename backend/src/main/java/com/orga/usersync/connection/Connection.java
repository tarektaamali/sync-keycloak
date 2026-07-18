package com.orga.usersync.connection;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "connection")
public class Connection {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(unique = true, nullable = false) private String name;
    @Enumerated(EnumType.STRING) private ConnectionType type;
    private String serverUrl;
    private String realm;
    private String baseDn;
    private String clientId;
    private String bindDn;
    private String userSearchBase;
    private String secretRef;
    private Instant createdAt;

    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getName() { return name; } public void setName(String v) { this.name = v; }
    public ConnectionType getType() { return type; } public void setType(ConnectionType v) { this.type = v; }
    public String getServerUrl() { return serverUrl; } public void setServerUrl(String v) { this.serverUrl = v; }
    public String getRealm() { return realm; } public void setRealm(String v) { this.realm = v; }
    public String getBaseDn() { return baseDn; } public void setBaseDn(String v) { this.baseDn = v; }
    public String getClientId() { return clientId; } public void setClientId(String v) { this.clientId = v; }
    public String getBindDn() { return bindDn; } public void setBindDn(String v) { this.bindDn = v; }
    public String getUserSearchBase() { return userSearchBase; } public void setUserSearchBase(String v) { this.userSearchBase = v; }
    public String getSecretRef() { return secretRef; } public void setSecretRef(String v) { this.secretRef = v; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { this.createdAt = v; }
}
