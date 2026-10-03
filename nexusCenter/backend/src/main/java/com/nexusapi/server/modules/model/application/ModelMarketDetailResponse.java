package com.nexusapi.server.modules.model.application;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 用户侧独立模型详情；只包含公开模型资料、接口文档和当前用户可见分组价格。 */
public record ModelMarketDetailResponse(
        ModelMarketItem model,
        List<ModelMarketInterface> interfaces,
        @JsonProperty("service_groups") List<ModelMarketGroupPrice> serviceGroups
) {
}
