package com.nexusapi.server.modules.notification.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.modules.notification.service.AnnouncementService;
import com.nexusapi.server.modules.notification.service.AnnouncementService.*;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@Validated
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/announcements")
public class AnnouncementAdminController {
    private final AnnouncementService service;
    public AnnouncementAdminController(AnnouncementService service) { this.service = service; }
    @GetMapping
    public ApiResponse<PageResponse<Announcement>> list(
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int page,
            @RequestParam(name = "page_size", defaultValue = "10") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(service.list(page, size));
    }
    @PostMapping
    public ApiResponse<Announcement> create(@Valid @RequestBody Input input,
            @AuthenticationPrincipal NexusUserPrincipal actor, HttpServletRequest request) {
        return ApiResponse.ok(service.create(input, actor.userId(), ClientRequestMetadata.from(request)));
    }
    @PutMapping("/{id}")
    public ApiResponse<Announcement> edit(@PathVariable UUID id, @Valid @RequestBody Edit input,
            @AuthenticationPrincipal NexusUserPrincipal actor, HttpServletRequest request) {
        return ApiResponse.ok(service.edit(id, input, actor.userId(), ClientRequestMetadata.from(request)));
    }
    @PostMapping("/{id}/publish")
    public ApiResponse<Announcement> publish(@PathVariable UUID id, @Valid @RequestBody Version input,
            @AuthenticationPrincipal NexusUserPrincipal actor, HttpServletRequest request) {
        return ApiResponse.ok(service.transition(id, input, true, actor.userId(), ClientRequestMetadata.from(request)));
    }
    @PostMapping("/{id}/withdraw")
    public ApiResponse<Announcement> withdraw(@PathVariable UUID id, @Valid @RequestBody Version input,
            @AuthenticationPrincipal NexusUserPrincipal actor, HttpServletRequest request) {
        return ApiResponse.ok(service.transition(id, input, false, actor.userId(), ClientRequestMetadata.from(request)));
    }
}
