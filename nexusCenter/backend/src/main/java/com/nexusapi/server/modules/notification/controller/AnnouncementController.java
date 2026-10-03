package com.nexusapi.server.modules.notification.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.modules.notification.service.AnnouncementService;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/announcements")
public class AnnouncementController {
    private final AnnouncementService service;
    public AnnouncementController(AnnouncementService service) { this.service = service; }
    @GetMapping
    public ApiResponse<AnnouncementService.Feed> list(@AuthenticationPrincipal NexusUserPrincipal user,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int page,
            @RequestParam(name = "page_size", defaultValue = "10") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(service.feed(user.userId(), page, size));
    }
    @PostMapping("/{id}/read")
    public ApiResponse<Map<String, Boolean>> read(@PathVariable UUID id, @AuthenticationPrincipal NexusUserPrincipal user) {
        service.markRead(user.userId(), id);
        return ApiResponse.ok(Map.of("read", true));
    }
}
