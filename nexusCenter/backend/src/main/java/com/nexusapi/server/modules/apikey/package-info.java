/**
 * API 令牌模块：负责一次性 Secret 签发、摘要存储、Bearer 鉴权、权限限制、状态管理和撤销。
 * 其他模块应通过 Service 使用本模块，不能跨模块直接调用 Mapper。
 */
package com.nexusapi.server.modules.apikey;
