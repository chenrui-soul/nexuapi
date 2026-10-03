package com.nexusapi.server.modules.auth.service;

import java.util.List;
import java.util.UUID;

public record SessionPrincipalData(UUID id, String displayName, String status, List<String> roles) {
    public boolean active() {
        return "active".equals(status);
    }
}
