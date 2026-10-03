/**
 * 对外开放能力入口包。
 *
 * <p>所有面向平台用户的模型能力接口都在此包内按能力类型扩展，
 * 例如 chat、image、video、audio 和 embedding。公共鉴权、路由、计费、
 * 重试及调用日志仍由上层 Gateway 服务统一处理，具体能力只负责协议适配。
 */
package com.nexusapi.server.modules.gateway.capability;
