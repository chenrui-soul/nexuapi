package com.nexusapi.server.modules.devicekey.controller;

import com.nexusapi.server.common.api.ApiResponse;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.devicekey.dto.DeviceActivationVerifyRequest;
import com.nexusapi.server.modules.devicekey.service.DeviceActivationKeyService;
import com.nexusapi.server.modules.devicekey.vo.DeviceActivationVerifyResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 应用激活校验入口；不依赖 API Key 或系统访问令牌认证。 */
@RestController
@RequestMapping("/api/v1/device-activation")
public class DeviceActivationKeyController {
    private final DeviceActivationKeyService service;
    public DeviceActivationKeyController(DeviceActivationKeyService service){this.service=service;}
    @PostMapping("/verify")
    public ApiResponse<DeviceActivationVerifyResponse> verify(@Valid @RequestBody DeviceActivationVerifyRequest request, HttpServletRequest http){
        return ApiResponse.ok(service.verify(request));
    }
}
