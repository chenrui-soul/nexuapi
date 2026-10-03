package com.nexusapi.server.modules.gateway.adapter;

import java.time.Instant;
import java.util.UUID;

/** 管理员维护的适配器目录行；implementationKey 指向后端实际注册的代码实现。 */
public class AdapterCatalogRow {
    private UUID id;
    private String adapterKey;
    private String displayName;
    private String capabilityType;
    private String implementationKey;
    private String description;
    private String status;
    private boolean builtIn;
    private Instant createdAt;
    private Instant updatedAt;
    private long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getAdapterKey() { return adapterKey; }
    public void setAdapterKey(String adapterKey) { this.adapterKey = adapterKey; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getCapabilityType() { return capabilityType; }
    public void setCapabilityType(String capabilityType) { this.capabilityType = capabilityType; }
    public String getImplementationKey() { return implementationKey; }
    public void setImplementationKey(String implementationKey) { this.implementationKey = implementationKey; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isBuiltIn() { return builtIn; }
    public void setBuiltIn(boolean builtIn) { this.builtIn = builtIn; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
