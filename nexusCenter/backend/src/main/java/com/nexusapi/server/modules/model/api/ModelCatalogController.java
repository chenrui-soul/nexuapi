package com.nexusapi.server.modules.model.api;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.modules.model.application.ModelCatalogItem;
import com.nexusapi.server.modules.model.application.ModelCatalogService;
import com.nexusapi.server.modules.auth.security.NexusUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/models")
public class ModelCatalogController {
    private final ModelCatalogService service;

    public ModelCatalogController(ModelCatalogService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<ModelCatalogItem>> list(Authentication authentication) {
        java.util.UUID userId = authentication != null
                && authentication.getPrincipal() instanceof NexusUserPrincipal principal
                ? principal.userId() : null;
        return ApiResponse.ok(service.listPublicModels(userId));
    }
}
