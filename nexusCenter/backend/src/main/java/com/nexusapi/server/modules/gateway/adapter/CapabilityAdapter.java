package com.nexusapi.server.modules.gateway.adapter;

/**
 * 统一能力适配器的标识契约。
 *
 * <p>适配器只负责平台标准协议与上游协议之间的转换；鉴权、路由、计费、
 * 重试和日志仍由 GatewayService 统一编排。</p>
 */
public interface CapabilityAdapter {
    /** 注册键，由模型维护中的适配器配置选择。 */
    String key();
}
