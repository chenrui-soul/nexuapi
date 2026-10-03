package com.nexusapi.server.modules.channel.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.channel.dto.AdminChannelRequest;
import com.nexusapi.server.modules.channel.service.AdminChannelService;
import com.nexusapi.server.modules.channel.vo.AdminChannelOperationResponse;
import com.nexusapi.server.modules.channel.vo.AdminChannelResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.List;

/** 管理员渠道和凭证轮换入口。 */
@Validated
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin")
public class AdminChannelController {
    private final AdminChannelService service;

    public AdminChannelController(AdminChannelService service) {
        this.service = service;
    }

    @GetMapping("/channels")
    ApiResponse<PageResponse<AdminChannelResponse>> listChannels(
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(required = false) String status,
            @RequestParam(name = "supplier_id", required = false) UUID supplierId
    ) {
        return ApiResponse.ok(service.listChannels(page, pageSize, query, status, supplierId));
    }

    @GetMapping("/channel-operations")
    ApiResponse<List<AdminChannelOperationResponse>> listChannelOperations() {
        return ApiResponse.ok(service.listOperations());
    }

    @PostMapping("/channels")
    ResponseEntity<ApiResponse<AdminChannelResponse>> createChannel(
            Authentication authentication,
            @Valid @RequestBody AdminChannelRequest body,
            HttpServletRequest request
    ) {
        AdminChannelResponse created = service.createChannel(
                principal(authentication).userId(), body, ClientRequestMetadata.from(request)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @PutMapping("/channels/{id}")
    ApiResponse<AdminChannelResponse> updateChannel(
            Authentication authentication,
            @PathVariable UUID id,
            @Valid @RequestBody AdminChannelRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.ok(service.updateChannel(
                principal(authentication).userId(), id, body, ClientRequestMetadata.from(request)
        ));
    }

    private NexusUserPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof NexusUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return principal;
    }
}
