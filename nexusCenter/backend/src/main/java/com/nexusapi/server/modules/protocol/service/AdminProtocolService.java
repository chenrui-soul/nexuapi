package com.nexusapi.server.modules.protocol.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.admin.service.AdminAuditService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.protocol.dto.AdminProtocolRequest;
import com.nexusapi.server.modules.protocol.dto.ProtocolSchemaField;
import com.nexusapi.server.modules.protocol.entity.AdminProtocolRow;
import com.nexusapi.server.modules.protocol.mapper.AdminProtocolMapper;
import com.nexusapi.server.modules.protocol.vo.AdminProtocolResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 公共接口文档管理员业务层；本层只维护接口定义，不写入模型接口关系。 */
@Service
public class AdminProtocolService {
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");
    private static final Pattern PUBLIC_PATH = Pattern.compile("^/[A-Za-z0-9._~!$&'()*+,;=:@{}\\[\\]/:-]+$");
    private static final Pattern FIELD_NAME = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$.-]{0,119}$");
    private static final Set<String> CAPABILITIES = Set.of("text", "image", "audio", "video", "embedding", "multimodal", "file");
    private static final Set<String> TRANSPORTS = Set.of("sync", "stream", "async_poll");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> STATUSES = Set.of("active", "disabled");
    /** 接口字段类型白名单；联合类型用于准确描述同一字段允许的多种 JSON 形态。 */
    private static final Set<String> FIELD_TYPES = Set.of(
            "string", "integer", "number", "boolean", "array", "object", "file", "null",
            "string|array", "object|array|string"
    );
    private static final int MAX_SCHEMA_JSON_LENGTH = 131_072;
    private static final int MAX_FIELD_DEPTH = 8;
    private static final int MAX_TOTAL_FIELDS = 300;
    private static final TypeReference<Map<String, List<ProtocolSchemaField>>> SCHEMA_TYPE = new TypeReference<>() { };

    private final AdminProtocolMapper mapper;
    private final AdminAuditService auditService;
    private final ObjectMapper objectMapper;

    public AdminProtocolService(AdminProtocolMapper mapper, AdminAuditService auditService, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** 查询协议目录；列表也返回字段树，管理员打开抽屉时不需要第二次加载。 */
    @Transactional(readOnly = true)
    public PageResponse<AdminProtocolResponse> list(
            int page, int pageSize, String query, String status, String capabilityType
    ) {
        String normalizedQuery = normalizeOptionalText(query, 100, "搜索内容");
        String normalizedStatus = status == null || status.isBlank() ? null : enumValue(status, STATUSES, "接口状态");
        String normalizedCapability = capabilityType == null || capabilityType.isBlank()
                ? null : enumValue(capabilityType, CAPABILITIES, "能力类型");
        int offset = Math.multiplyExact(page - 1, pageSize);
        return new PageResponse<>(
                mapper.findPage(normalizedQuery, normalizedStatus, normalizedCapability, offset, pageSize)
                        .stream().map(this::toResponse).toList(),
                mapper.countPage(normalizedQuery, normalizedStatus, normalizedCapability),
                page,
                pageSize
        );
    }

    @Transactional
    public AdminProtocolResponse create(
            UUID actorUserId, AdminProtocolRequest request, ClientRequestMetadata metadata
    ) {
        AdminProtocolRow row = normalize(request, false);
        row.setId(UUID.randomUUID());
        if (mapper.countCode(row.getInterfaceCode(), null) != 0) {
            throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "接口编码已存在", null);
        }
        mapper.insert(row);
        AdminProtocolResponse created = toResponse(require(row.getId()));
        auditService.record(actorUserId, "admin.interface.create", "api_interface", row.getId(), null, created, metadata);
        return created;
    }

    @Transactional
    public AdminProtocolResponse update(
            UUID actorUserId, UUID id, AdminProtocolRequest request, ClientRequestMetadata metadata
    ) {
        AdminProtocolResponse before = toResponse(require(id));
        if (request.version() == null) {
            throw validation("更新接口必须提供 version");
        }
        AdminProtocolRow row = normalize(request, true);
        row.setId(id);
        if (mapper.countCode(row.getInterfaceCode(), id) != 0) {
            throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "接口编码已存在", null);
        }
        if (mapper.update(row) != 1) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }
        AdminProtocolResponse updated = toResponse(require(id));
        auditService.record(actorUserId, "admin.interface.update", "api_interface", id, before, updated, metadata);
        return updated;
    }

    private AdminProtocolRow normalize(AdminProtocolRequest request, boolean requireVersion) {
        AdminProtocolRow row = new AdminProtocolRow();
        String code = normalizeText(request.interfaceCode(), 64, "接口编码").toLowerCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw validation("接口编码只能使用小写字母、数字和下划线，并以字母开头");
        }
        String publicPath = normalizeText(request.publicPath(), 240, "对外接口路径");
        if (!PUBLIC_PATH.matcher(publicPath).matches() || publicPath.contains("..") || publicPath.contains("//")) {
            throw validation("对外接口路径必须是安全的绝对路径模板");
        }
        List<ProtocolSchemaField> requestFields = normalizedFields(request.requestFields(), "请求参数");
        List<ProtocolSchemaField> responseFields = normalizedFields(request.responseFields(), "响应参数");

        row.setInterfaceCode(code);
        row.setInterfaceName(normalizeText(request.interfaceName(), 120, "接口名称"));
        row.setInterfaceVersion(normalizeText(request.interfaceVersion(), 32, "接口版本"));
        row.setCapabilityType(enumValue(request.capabilityType(), CAPABILITIES, "能力类型"));
        row.setTransportMode(enumValue(request.transportMode(), TRANSPORTS, "交互方式"));
        row.setHttpMethod(enumValue(request.httpMethod(), METHODS, "HTTP 方法").toUpperCase(Locale.ROOT));
        row.setPublicPath(publicPath);
        row.setRequestContentType(normalizeText(request.requestContentType(), 80, "请求 Content-Type"));
        row.setRequestSchemaJson(schemaJson(requestFields));
        row.setResponseSchemaJson(schemaJson(responseFields));
        row.setDescription(normalizeOptionalText(request.description(), 1000, "接口说明"));
        row.setStatus(enumValue(request.status(), STATUSES, "接口状态"));
        row.setVersion(requireVersion ? request.version() : 0L);
        return row;
    }

    private List<ProtocolSchemaField> normalizedFields(List<ProtocolSchemaField> fields, String label) {
        List<ProtocolSchemaField> normalized = fields == null ? List.of() : List.copyOf(fields);
        int count = validateFieldTree(normalized, 1, label);
        if (count > MAX_TOTAL_FIELDS) {
            throw validation(label + "字段总数不能超过 " + MAX_TOTAL_FIELDS);
        }
        return normalized;
    }

    private int validateFieldTree(List<ProtocolSchemaField> fields, int depth, String label) {
        if (depth > MAX_FIELD_DEPTH) {
            throw validation(label + "嵌套层级不能超过 " + MAX_FIELD_DEPTH);
        }
        int total = 0;
        Set<String> siblingNames = new java.util.HashSet<>();
        for (ProtocolSchemaField field : fields) {
            total++;
            String name = normalizeText(field.name(), 120, label + "字段名称");
            if (!FIELD_NAME.matcher(name).matches()) {
                throw validation(label + "字段名称格式无效");
            }
            if (!siblingNames.add(name)) {
                throw validation(label + "同一层级存在重复字段：" + name);
            }
            String type = enumValue(field.type(), FIELD_TYPES, label + "字段类型");
            if (field.minimum() != null && field.maximum() != null
                    && field.minimum().compareTo(field.maximum()) > 0) {
                throw validation(label + "字段 " + name + " 的最小值不能大于最大值");
            }
            List<ProtocolSchemaField> children = field.children() == null ? List.of() : field.children();
            if (!children.isEmpty() && !("object".equals(type) || "array".equals(type))) {
                throw validation(label + "字段 " + name + " 只有 object 或 array 类型可以包含子字段");
            }
            total += validateFieldTree(children, depth + 1, label);
        }
        return total;
    }

    private String schemaJson(List<ProtocolSchemaField> fields) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("fields", fields));
            if (json.length() > MAX_SCHEMA_JSON_LENGTH) {
                throw validation("接口字段定义过大");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize protocol schema", exception);
        }
    }

    private List<ProtocolSchemaField> readFields(String json) {
        try {
            Map<String, List<ProtocolSchemaField>> schema = objectMapper.readValue(json, SCHEMA_TYPE);
            return List.copyOf(schema.getOrDefault("fields", List.of()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid protocol schema", exception);
        }
    }

    private AdminProtocolRow require(UUID id) {
        AdminProtocolRow row = mapper.findById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND, "接口不存在", null);
        }
        return row;
    }

    private AdminProtocolResponse toResponse(AdminProtocolRow row) {
        return new AdminProtocolResponse(
                row.getId(), row.getInterfaceCode(), row.getInterfaceName(), row.getInterfaceVersion(),
                row.getCapabilityType(), row.getTransportMode(), row.getHttpMethod(), row.getPublicPath(),
                row.getRequestContentType(), row.getDescription(), row.getStatus(),
                readFields(row.getRequestSchemaJson()), readFields(row.getResponseSchemaJson()),
                row.getCreatedAt(), row.getUpdatedAt(), row.getVersion()
        );
    }

    private String enumValue(String value, Set<String> allowed, String field) {
        String normalized = normalizeText(value, 80, field);
        String comparable = METHODS == allowed ? normalized.toUpperCase(Locale.ROOT) : normalized.toLowerCase(Locale.ROOT);
        if (!allowed.contains(comparable)) {
            throw validation(field + "不支持该值");
        }
        return comparable;
    }

    private String normalizeText(String value, int maxLength, String field) {
        String normalized = normalizeOptionalText(value, maxLength, field);
        if (normalized == null) {
            throw validation(field + "不能为空");
        }
        return normalized;
    }

    private String normalizeOptionalText(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw validation(field + "格式无效");
        }
        return normalized;
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }
}
