package com.nexusapi.server.modules.system.api;

import com.nexusapi.server.common.api.ApiResponse;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {
    private final ObjectProvider<BuildProperties> buildProperties;

    public SystemController(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    @GetMapping("/info")
    ApiResponse<Map<String, Object>> info() {
        BuildProperties build = buildProperties.getIfAvailable();
        return ApiResponse.ok(Map.of(
                "name", "NEXUS API Server",
                "version", build == null ? "development" : build.getVersion(),
                "time", Instant.now(),
                "status", "architecture-ready"
        ));
    }
}

