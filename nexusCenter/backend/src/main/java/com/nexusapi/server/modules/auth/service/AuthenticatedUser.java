package com.nexusapi.server.modules.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AuthenticatedUser(
        UUID id,
        String name,
        String email,
        String status,
        List<String> roles,
        Instant createdAt
) {
}
