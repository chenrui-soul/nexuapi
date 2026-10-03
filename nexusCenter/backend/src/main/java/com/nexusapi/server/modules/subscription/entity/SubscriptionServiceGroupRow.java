package com.nexusapi.server.modules.subscription.entity;

import java.util.UUID;

/** 套餐或订阅快照关联的用户可见服务分组。 */
public class SubscriptionServiceGroupRow {
    /** 服务分组主键。 */
    private UUID id;
    /** 服务分组稳定编码。 */
    private String code;
    /** 服务分组展示名称。 */
    private String name;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}

