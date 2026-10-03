package com.nexusapi.server.modules.auth.security;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.UUID;

public record NexusUserPrincipal(UUID userId, String displayName, List<String> roles) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}
