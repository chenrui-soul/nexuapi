package com.nexusapi.server.modules.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.auth.service.CaptchaCodeGenerator;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 接口文档权限、字段注释、嵌套字段、模型关联归属和乐观锁集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminProtocolIntegrationTest.FixedCaptchaConfiguration.class)
class AdminProtocolIntegrationTest {
    private static final String CAPTCHA_CODE = "ACEF";
    private static final String PASSWORD = "StrongPassword!2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RedisConnectionFactory redisConnectionFactory;

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("TRUNCATE TABLE users, ai_models CASCADE");
        jdbcTemplate.update("DELETE FROM api_interfaces WHERE interface_code IN ('demo_video', 'demo_file', 'demo_union')");
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void apiInterfacesExposeRequestAndResponseDescriptionsToAdminOnly() throws Exception {
        RegisteredUser ordinary = register("protocol-user@example.com");
        mockMvc.perform(get("/api/v1/admin/api-interfaces").cookie(ordinary.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"));

        RegisteredUser admin = registerAdmin("protocol-admin@example.com");
        mockMvc.perform(get("/api/v1/admin/api-interfaces")
                .cookie(admin.session()).param("capability_type", "video"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items[?(@.interface_code == 'openai_video_list')]").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].request_fields[0].description").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].response_fields[0].description").isNotEmpty());
    }

    @Test
    void openAiChatDocumentsTheGpt56RequestSubsetAndBothResponseModes() {
        List<String> requestNames = jdbcTemplate.queryForList("""
                SELECT field ->> 'name'
                  FROM api_interfaces interface,
                       jsonb_array_elements(interface.request_schema -> 'fields') WITH ORDINALITY AS item(field, position)
                 WHERE interface.interface_code = 'openai_chat'
                 ORDER BY position
                """, String.class);
        assertThat(requestNames).containsExactly(
                "model", "messages", "stream", "temperature", "max_tokens", "tools", "top_p"
        );

        List<String> responseNames = jdbcTemplate.queryForList("""
                SELECT field ->> 'name'
                  FROM api_interfaces interface,
                       jsonb_array_elements(interface.response_schema -> 'fields') WITH ORDINALITY AS item(field, position)
                 WHERE interface.interface_code = 'openai_chat'
                 ORDER BY position
                """, String.class);
        assertThat(responseNames).containsExactly(
                "id", "object", "created", "model", "choices", "usage", "system_fingerprint"
        );

        assertThat(jdbcTemplate.queryForObject("""
                 SELECT count(*)
                   FROM api_interfaces interface,
                        jsonb_array_elements(interface.response_schema -> 'fields') choice_field,
                       jsonb_array_elements(choice_field -> 'children') child
                 WHERE interface.interface_code = 'openai_chat'
                   AND choice_field ->> 'name' = 'choices'
                   AND child ->> 'name' IN ('message', 'delta')
                 """, Integer.class)).isEqualTo(2);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM api_interfaces interface,
                       jsonb_array_elements(interface.response_schema -> 'fields') choice_field,
                       jsonb_array_elements(choice_field -> 'children') child
                 WHERE interface.interface_code = 'openai_chat'
                   AND choice_field ->> 'name' = 'choices'
                   AND child ->> 'name' IN ('native_finish_reason')
                """, Integer.class)).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM api_interfaces interface,
                       jsonb_array_elements(interface.response_schema -> 'fields') choice_field,
                       jsonb_array_elements(choice_field -> 'children') choice_child,
                       jsonb_array_elements(choice_child -> 'children') delta_child,
                       jsonb_array_elements(delta_child -> 'children') tool_call_child
                 WHERE interface.interface_code = 'openai_chat'
                   AND choice_field ->> 'name' = 'choices'
                   AND choice_child ->> 'name' = 'delta'
                   AND delta_child ->> 'name' = 'tool_calls'
                   AND tool_call_child ->> 'name' IN ('index', 'id', 'type', 'function')
                """, Integer.class)).isEqualTo(4);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM api_interfaces interface,
                       jsonb_array_elements(interface.response_schema -> 'fields') choice_field,
                       jsonb_array_elements(choice_field -> 'children') choice_child,
                       jsonb_array_elements(choice_child -> 'children') message_child
                 WHERE interface.interface_code = 'openai_chat'
                   AND choice_field ->> 'name' = 'choices'
                   AND choice_child ->> 'name' = 'message'
                   AND message_child ->> 'name' = 'reasoning_content'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void openAiImagesDocumentsTheConfirmedRequestAndResponseFields() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT field ->> 'name'
                  FROM api_interfaces interface,
                       jsonb_array_elements(interface.request_schema -> 'fields') WITH ORDINALITY AS item(field, position)
                 WHERE interface.interface_code = 'openai_images'
                 ORDER BY position
                """, String.class)).containsExactly(
                "aspect_ratio", "images", "model", "n", "prompt", "quality", "extra_params"
        );

        Map<String, Object> interfaceMetadata = jdbcTemplate.queryForMap("""
                SELECT public_path, request_content_type, capability_type, transport_mode,
                       jsonb_array_length(response_schema -> 'fields') AS response_field_count
                  FROM api_interfaces
                 WHERE interface_code = 'openai_images'
                """);
        assertThat(interfaceMetadata)
                .containsEntry("public_path", "/v1/images/generations")
                .containsEntry("request_content_type", "multipart/form-data")
                .containsEntry("capability_type", "image")
                .containsEntry("transport_mode", "sync")
                .containsEntry("response_field_count", 7);

        assertThat(jdbcTemplate.queryForList("""
                SELECT field ->> 'name'
                  FROM api_interfaces interface,
                       jsonb_array_elements(interface.response_schema -> 'fields') WITH ORDINALITY AS item(field, position)
                 WHERE interface.interface_code = 'openai_images'
                 ORDER BY position
                """, String.class)).containsExactly(
                "created", "background", "output_format", "quality", "size", "data", "usage"
        );

        assertThat(jdbcTemplate.queryForList("""
                SELECT child ->> 'name'
                  FROM api_interfaces interface,
                       jsonb_array_elements(interface.response_schema -> 'fields') field,
                       jsonb_array_elements(field -> 'children') WITH ORDINALITY AS item(child, position)
                 WHERE interface.interface_code = 'openai_images'
                   AND field ->> 'name' = 'data'
                 ORDER BY position
                """, String.class)).containsExactly("url", "b64_json", "revised_prompt");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM api_interfaces WHERE interface_code = 'grouk_image'",
                Integer.class
        )).isZero();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT field #>> '{children,0,path}'
                  FROM api_interfaces interface,
                       jsonb_array_elements(interface.request_schema -> 'fields') field
                 WHERE interface.interface_code = 'openai_images'
                   AND field ->> 'name' = 'images'
                """, String.class)).isEqualTo("images[]");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT field #>> '{children,0,type}'
                  FROM api_interfaces interface,
                       jsonb_array_elements(interface.request_schema -> 'fields') field
                 WHERE interface.interface_code = 'openai_images'
                   AND field ->> 'name' = 'images'
                """, String.class)).isEqualTo("file");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT field #>> '{children,0,path}'
                  FROM api_interfaces interface,
                       jsonb_array_elements(interface.request_schema -> 'fields') field
                 WHERE interface.interface_code = 'openai_images'
                   AND field ->> 'name' = 'extra_params'
                """, String.class)).isEqualTo("extra_params.resolution");

    }

    @Test
    void jimengVideoDocumentsSeedance25RequestFieldsAndNestedAudioOption() {
        List<String> requestNames = jdbcTemplate.queryForList("""
                SELECT field ->> 'name'
                  FROM api_interfaces interface,
                       jsonb_array_elements(interface.request_schema -> 'fields') WITH ORDINALITY AS item(field, position)
                 WHERE interface.interface_code = 'jimeng_video'
                 ORDER BY position
                """, String.class);
        assertThat(requestNames).containsExactly(
                "model", "prompt", "aspect_ratio", "duration", "resolution",
                "first_frame_url", "last_frame_url", "input_references", "extra_params"
        );

        assertThat(jdbcTemplate.queryForObject("""
                SELECT field ->> 'description'
                  FROM api_interfaces interface,
                       jsonb_array_elements(interface.request_schema -> 'fields') field
                 WHERE interface.interface_code = 'jimeng_video'
                   AND field ->> 'name' = 'duration'
                """, String.class)).contains("4–30 秒");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT field #>> '{children,0,default_value}'
                  FROM api_interfaces interface,
                       jsonb_array_elements(interface.request_schema -> 'fields') field
                 WHERE interface.interface_code = 'jimeng_video'
                   AND field ->> 'name' = 'extra_params'
                   AND field #>> '{children,0,name}' = 'generate_audio'
                """, String.class)).isEqualTo("true");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT field #>> '{children,0,path}'
                  FROM api_interfaces interface,
                       jsonb_array_elements(interface.request_schema -> 'fields') field
                 WHERE interface.interface_code = 'jimeng_video'
                   AND field ->> 'name' = 'input_references'
                """, String.class)).isEqualTo("input_references[]");
    }

    @Test
    void videoInterfacesDocumentAsyncLifecycleAndGrokExtendedRequestFields() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT field ->> 'name'
                  FROM api_interfaces interface,
                       jsonb_array_elements(interface.response_schema -> 'fields') WITH ORDINALITY AS item(field, position)
                 WHERE interface.interface_code = 'jimeng_video'
                 ORDER BY position
                """, String.class)).containsExactly(
                "id", "task_id", "object", "model", "status", "progress",
                "created_at", "completed_at", "prompt", "video_url", "error"
        );
        assertThat(jdbcTemplate.queryForList("""
                SELECT field ->> 'name'
                  FROM api_interfaces interface,
                       jsonb_array_elements(interface.request_schema -> 'fields') WITH ORDINALITY AS item(field, position)
                 WHERE interface.interface_code = 'grok_video'
                 ORDER BY position
                """, String.class)).containsExactly(
                "model", "prompt", "image_url", "duration", "aspect_ratio", "resolution",
                "first_frame_url", "last_frame_url", "input_references", "extra_params"
        );
        assertThat(jdbcTemplate.queryForObject("""
                SELECT field #>> '{children,0,name}'
                  FROM api_interfaces interface,
                       jsonb_array_elements(interface.response_schema -> 'fields') field
                 WHERE interface.interface_code = 'jimeng_video'
                   AND field ->> 'name' = 'error'
                """, String.class)).isEqualTo("code");
    }

    @Test
    void adminCanCreateNestedInterfaceAndModelOwnsTheAssociation() throws Exception {
        RegisteredUser admin = registerAdmin("protocol-write@example.com");

        MvcResult createdResult = mockMvc.perform(post("/api/v1/admin/api-interfaces")
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(protocolPayload(null, "演示视频接口"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.interface_code").value("demo_video"))
                .andExpect(jsonPath("$.data.request_fields[1].children[0].description").value("参考图地址"))
                .andReturn();
        JsonNode created = data(createdResult);
        UUID interfaceId = UUID.fromString(created.path("id").asText());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT request_schema -> 'fields' -> 1 -> 'children' -> 0 ->> 'description' FROM api_interfaces WHERE id = ?",
                String.class, interfaceId
        )).isEqualTo("参考图地址");

        MvcResult modelResult = mockMvc.perform(post("/api/v1/admin/models")
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(modelPayload(interfaceId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.interfaces[0].interface_code").value("demo_video"))
                .andReturn();
        UUID modelId = UUID.fromString(data(modelResult).path("id").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_interfaces WHERE interface_id = ? AND model_id = ?",
                Integer.class, interfaceId, modelId
        )).isEqualTo(1);

        Map<String, Object> updatedPayload = protocolPayload(0L, "演示视频接口 V2");
        mockMvc.perform(put("/api/v1/admin/api-interfaces/{id}", interfaceId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json(updatedPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interface_name").value("演示视频接口 V2"))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(put("/api/v1/admin/api-interfaces/{id}", interfaceId)
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json(updatedPayload)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFIGURATION_VERSION_CONFLICT"));
    }

    @Test
    void adminCanCreateFileTypedField() throws Exception {
        RegisteredUser admin = registerAdmin("protocol-file@example.com");

        mockMvc.perform(post("/api/v1/admin/api-interfaces")
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(protocolPayloadWithFileField())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.request_fields[0].type").value("file"));
    }

    @Test
    void adminCanCreateUnionTypedField() throws Exception {
        RegisteredUser admin = registerAdmin("protocol-union@example.com");
        Map<String, Object> payload = protocolPayload(null, "联合类型接口");
        payload.put("interface_code", "demo_union");
        payload.put("public_path", "/v1/union/demo");
        payload.put("request_fields", List.of(
                field("input", "input", "object|array|string", true, "支持多种 JSON 输入", List.of())
        ));

        mockMvc.perform(post("/api/v1/admin/api-interfaces")
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.request_fields[0].type").value("object|array|string"));
    }

    @Test
    void adminCanCreateStringOrArrayFieldForEmbeddings() throws Exception {
        RegisteredUser admin = registerAdmin("protocol-string-array@example.com");
        Map<String, Object> payload = protocolPayload(null, "向量联合类型接口");
        payload.put("interface_code", "demo_embedding_union");
        payload.put("capability_type", "embedding");
        payload.put("public_path", "/v1/embeddings/demo");
        payload.put("request_fields", List.of(
                field("input", "input", "string|array", true, "单个文本或文本数组", List.of())
        ));

        mockMvc.perform(post("/api/v1/admin/api-interfaces")
                        .cookie(admin.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.request_fields[0].type").value("string|array"));
    }

    private Map<String, Object> protocolPayload(Long version, String name) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("interface_code", "demo_video");
        payload.put("interface_name", name);
        payload.put("interface_version", "v1");
        payload.put("capability_type", "video");
        payload.put("transport_mode", "async_poll");
        payload.put("http_method", "POST");
        payload.put("public_path", "/v1/videos/demo");
        payload.put("request_content_type", "application/json");
        payload.put("description", "用于验证协议字段说明和异步视频任务结构");
        payload.put("status", "active");
        payload.put("request_fields", List.of(
                field("prompt", "prompt", "string", true, "视频生成提示词", List.of()),
                field("input", "input", "object", false, "视频输入资源", List.of(
                        field("image_url", "input.image_url", "string", false, "参考图地址", List.of())
                ))
        ));
        payload.put("response_fields", List.of(
                field("task_id", "task_id", "string", true, "异步任务编号", List.of()),
                field("status", "status", "string", true, "任务状态", List.of())
        ));
        if (version != null) payload.put("version", version);
        return payload;
    }

    private Map<String, Object> protocolPayloadWithFileField() {
        Map<String, Object> payload = protocolPayload(null, "文件上传接口");
        payload.put("interface_code", "demo_file");
        payload.put("public_path", "/v1/files/demo");
        payload.put("request_content_type", "multipart/form-data");
        payload.put("request_fields", List.of(
                field("file", "file", "file", true, "待上传文件", List.of())
        ));
        return payload;
    }

    private Map<String, Object> modelPayload(UUID interfaceId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("public_name", "video-demo");
        payload.put("display_name", "Video Demo");
        payload.put("provider", "demo");
        payload.put("capability_type", "video");
        payload.put("input_modalities", List.of("video"));
        payload.put("output_modalities", List.of("video"));
        payload.put("context_window", null);
        payload.put("max_output_tokens", null);
        payload.put("supports_streaming", false);
        payload.put("supports_tools", false);
        payload.put("supports_structured_output", false);
        payload.put("input_price", 0);
        payload.put("output_price", 0);
        payload.put("cached_input_price", 0);
        payload.put("price_unit", "request");
        payload.put("interface_ids", List.of(interfaceId));
        payload.put("public_visible", true);
        payload.put("status", "active");
        return payload;
    }

    private Map<String, Object> field(
            String name, String path, String type, boolean required, String description,
            List<Map<String, Object>> children
    ) {
        return Map.of(
                "name", name, "path", path, "type", type, "required", required,
                "description", description, "deprecated", false, "sensitive", false,
                "children", children
        );
    }

    private RegisteredUser registerAdmin(String email) throws Exception {
        RegisteredUser user = register(email);
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_code) VALUES (?, 'admin')", user.id());
        return user;
    }

    private RegisteredUser register(String email) throws Exception {
        MvcResult captcha = mockMvc.perform(get("/api/v1/auth/captcha").param("scene", "register"))
                .andExpect(status().isOk()).andReturn();
        String challengeId = objectMapper.readTree(captcha.getResponse().getContentAsString())
                .at("/data/challenge_id").asText();
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Protocol Test User", "email", email, "password", PASSWORD,
                                "challenge_id", challengeId, "captcha_code", CAPTCHA_CODE
                        ))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(registration.getResponse().getContentAsString());
        return new RegisteredUser(
                UUID.fromString(body.at("/data/user/id").asText()),
                registration.getResponse().getCookie("NEXUS_SESSION")
        );
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    record RegisteredUser(UUID id, Cookie session) { }

    @TestConfiguration
    static class FixedCaptchaConfiguration {
        @Bean @Primary
        CaptchaCodeGenerator fixedCaptchaCodeGenerator() {
            return length -> CAPTCHA_CODE;
        }
    }
}
