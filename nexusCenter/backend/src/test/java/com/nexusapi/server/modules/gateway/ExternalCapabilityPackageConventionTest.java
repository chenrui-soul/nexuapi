package com.nexusapi.server.modules.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 保证所有 `/v1` 对外模型能力入口统一归入 capability 包。 */
class ExternalCapabilityPackageConventionTest {
    private static final String MODULES_PACKAGE = "com.nexusapi.server.modules";
    private static final String CAPABILITY_PACKAGE = "com.nexusapi.server.modules.gateway.capability";

    @Test
    void publicModelCapabilityControllersStayInCapabilityPackage() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<String> misplacedControllers = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents(MODULES_PACKAGE)) {
            Class<?> controllerType = Class.forName(candidate.getBeanClassName());
            RequestMapping mapping = controllerType.getAnnotation(RequestMapping.class);
            if (mapping == null || !isPublicCapabilityRoot(mapping)) {
                continue;
            }
            if (!controllerType.getPackageName().startsWith(CAPABILITY_PACKAGE)) {
                misplacedControllers.add(controllerType.getName());
            }
        }

        assertTrue(misplacedControllers.isEmpty(),
                "以下 /v1 对外能力 Controller 必须移动到 " + CAPABILITY_PACKAGE + ": " + misplacedControllers);
    }

    private boolean isPublicCapabilityRoot(RequestMapping mapping) {
        return Arrays.stream(mapping.value()).anyMatch(this::isV1Path)
                || Arrays.stream(mapping.path()).anyMatch(this::isV1Path);
    }

    private boolean isV1Path(String path) {
        return "/v1".equals(path) || path.startsWith("/v1/");
    }
}
