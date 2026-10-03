package com.nexusapi.server.modules.devicekey.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.modules.admin.service.AdminAuditService;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.devicekey.dto.*;
import com.nexusapi.server.modules.devicekey.service.DeviceActivationKeyService;
import com.nexusapi.server.modules.devicekey.vo.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/device-activation-keys")
public class AdminDeviceActivationKeyController {
    private final DeviceActivationKeyService service;
    public AdminDeviceActivationKeyController(DeviceActivationKeyService service){this.service=service;}
    @GetMapping public ApiResponse<PageResponse<DeviceActivationKeyItemResponse>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String query,
            Authentication auth, HttpServletRequest http){
        return ApiResponse.ok(service.list(page,pageSize,query,principal(auth),ClientRequestMetadata.from(http)));
    }
    @PostMapping public ApiResponse<DeviceActivationKeyService.DeviceActivationKeyCreated> create(@Valid @RequestBody DeviceActivationKeyCreateRequest req, Authentication auth, HttpServletRequest http){return ApiResponse.ok(service.create(req,principal(auth),ClientRequestMetadata.from(http)));}
    @PutMapping("/{id}/status") public ApiResponse<DeviceActivationKeyItemResponse> status(@PathVariable UUID id,@Valid @RequestBody DeviceActivationKeyStatusRequest req,Authentication auth,HttpServletRequest http){return ApiResponse.ok(service.changeStatus(id,req,principal(auth),ClientRequestMetadata.from(http)));}
    @DeleteMapping("/{id}") public ApiResponse<Map<String, Boolean>> delete(@PathVariable UUID id, Authentication auth, HttpServletRequest http){
        return ApiResponse.ok(Map.of("deleted",service.delete(id,principal(auth),ClientRequestMetadata.from(http))));
    }
    private UUID principal(Authentication a){return ((NexusUserPrincipal)a.getPrincipal()).userId();}
}
