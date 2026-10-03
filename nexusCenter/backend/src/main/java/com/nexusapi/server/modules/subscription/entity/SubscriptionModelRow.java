package com.nexusapi.server.modules.subscription.entity;

import java.util.UUID;

/** 套餐或订阅快照关联的模型展示信息。 */
public class SubscriptionModelRow {
    /** 模型主键。 */
    private UUID id;
    /** 对外调用使用的模型名称。 */
    private String publicName;
    /** 用户界面展示名称。 */
    private String displayName;
    /** 模型能力类型。 */
    private String capabilityType;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getPublicName() { return publicName; }
    public void setPublicName(String publicName) { this.publicName = publicName; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getCapabilityType() { return capabilityType; }
    public void setCapabilityType(String capabilityType) { this.capabilityType = capabilityType; }
}
