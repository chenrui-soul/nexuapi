package com.nexusapi.server.modules.system;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import java.util.Map;

@RestController
public class PlatformSettingsController {
    private final PlatformSettingsService service;
    public PlatformSettingsController(PlatformSettingsService service) { this.service = service; }

    @GetMapping("/api/v1/system/registration")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> registration() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(Map.of("registration_enabled", service.get().registrationEnabled())));
    }

    @GetMapping("/api/v1/admin/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PlatformSettingsService.Settings> get() { return ApiResponse.ok(service.get()); }

    @PutMapping("/api/v1/admin/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PlatformSettingsService.Settings> update(@Valid @RequestBody PlatformSettingsService.Update input,
            @AuthenticationPrincipal NexusUserPrincipal actor, HttpServletRequest request) {
        return ApiResponse.ok(service.update(input, actor.userId(), ClientRequestMetadata.from(request)));
    }
}
