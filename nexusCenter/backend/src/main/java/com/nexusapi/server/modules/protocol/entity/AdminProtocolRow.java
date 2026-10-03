package com.nexusapi.server.modules.protocol.entity;

import java.time.Instant;
import java.util.UUID;

/** MyBatis 接口定义行；请求和响应字段树以 JSON 文本映射 PostgreSQL JSONB。 */
public class AdminProtocolRow {
    /** 接口定义全局唯一标识。 */
    private UUID id;
    /** 稳定接口编码，例如 openai_chat、jimeng_video。 */
    private String interfaceCode;
    /** 管理端和接口文档显示名称。 */
    private String interfaceName;
    /** 接口自身版本标签。 */
    private String interfaceVersion;
    /** 适用模型能力类型。 */
    private String capabilityType;
    /** 交互方式：sync、stream 或 async_poll。 */
    private String transportMode;
    /** 对外 HTTP 方法。 */
    private String httpMethod;
    /** 平台对外接口路径模板。 */
    private String publicPath;
    /** 请求 Content-Type。 */
    private String requestContentType;
    /** 带字段说明的请求字段树 JSON。 */
    private String requestSchemaJson;
    /** 带字段说明的响应字段树 JSON。 */
    private String responseSchemaJson;
    /** 接口用途和调用流程说明。 */
    private String description;
    /** 管理状态：active 或 disabled。 */
    private String status;
    /** 创建时间。 */
    private Instant createdAt;
    /** 最后更新时间。 */
    private Instant updatedAt;
    /** 管理员更新使用的乐观锁版本号。 */
    private long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getInterfaceCode() { return interfaceCode; }
    public void setInterfaceCode(String interfaceCode) { this.interfaceCode = interfaceCode; }
    public String getInterfaceName() { return interfaceName; }
    public void setInterfaceName(String interfaceName) { this.interfaceName = interfaceName; }
    public String getInterfaceVersion() { return interfaceVersion; }
    public void setInterfaceVersion(String interfaceVersion) { this.interfaceVersion = interfaceVersion; }
    public String getCapabilityType() { return capabilityType; }
    public void setCapabilityType(String capabilityType) { this.capabilityType = capabilityType; }
    public String getTransportMode() { return transportMode; }
    public void setTransportMode(String transportMode) { this.transportMode = transportMode; }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }
    public String getPublicPath() { return publicPath; }
    public void setPublicPath(String publicPath) { this.publicPath = publicPath; }
    public String getRequestContentType() { return requestContentType; }
    public void setRequestContentType(String requestContentType) { this.requestContentType = requestContentType; }
    public String getRequestSchemaJson() { return requestSchemaJson; }
    public void setRequestSchemaJson(String requestSchemaJson) { this.requestSchemaJson = requestSchemaJson; }
    public String getResponseSchemaJson() { return responseSchemaJson; }
    public void setResponseSchemaJson(String responseSchemaJson) { this.responseSchemaJson = responseSchemaJson; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
