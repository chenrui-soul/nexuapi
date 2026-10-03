package com.nexusapi.server.modules.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** 模型批量启停请求，限制单次操作规模，避免误操作影响整张模型目录。 */
public record AdminModelBatchStatusRequest(
        @NotEmpty @Size(max = 100) @JsonProperty("model_ids") List<@Valid UUID> modelIds,
        @NotBlank @Size(max = 24) String status
) {
}
