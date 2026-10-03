package com.nexusapi.server.modules.fileupload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.apikey.service.ApiKeyAuthenticationService;
import com.nexusapi.server.modules.fileupload.client.CaicaiFileUploadClient;
import com.nexusapi.server.modules.gateway.upstream.UpstreamCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 独立文件上传入口、无 API Key 边界和数据库状态收口测试。 */
@SpringBootTest
@AutoConfigureMockMvc
class FileUploadIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean ApiKeyAuthenticationService authenticationService;
    @MockitoBean CaicaiFileUploadClient uploadClient;

    @BeforeEach
    void clearRecords() {
        jdbcTemplate.execute("TRUNCATE TABLE file_upload_records");
    }

    @Test
    void uploadReturnsUpstreamJsonAndPersistsMetadata() throws Exception {
        byte[] content = "file-content".getBytes(StandardCharsets.UTF_8);
        when(uploadClient.upload(any())).thenReturn(new CaicaiFileUploadClient.UploadResponse(
                200, objectMapper.readTree("""
                        {"id":"file_123","object":"file","filename":"test.txt","url":"https://files.example/file_123"}
                        """)
        ));

        mockMvc.perform(multipart("/v1/files/upload")
                        .file(new MockMultipartFile("file", "folder/test.txt", "text/plain", content))
                        .header("X-Request-Id", "req_file_upload_success_001"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "req_file_upload_success_001"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.id").value("file_123"))
                .andExpect(jsonPath("$.url").value("https://files.example/file_123"));

        verify(uploadClient).upload(any());
        verifyNoInteractions(authenticationService);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT request_id, original_filename, content_type, file_size_bytes,
                       content_sha256, upstream_provider, upstream_file_id, upstream_file_url,
                       status, upstream_status, response_body->>'id' AS response_id
                  FROM file_upload_records
                 WHERE request_id = 'req_file_upload_success_001'
                """))
                .containsEntry("request_id", "req_file_upload_success_001")
                .containsEntry("original_filename", "test.txt")
                .containsEntry("content_type", "text/plain")
                .containsEntry("file_size_bytes", (long) content.length)
                .containsEntry("content_sha256", "2239ce4df9ee8db012834642ec801b55ba2c92b28bdd11f4d73d9c55d39f3b0a")
                .containsEntry("upstream_provider", "caicai")
                .containsEntry("upstream_file_id", "file_123")
                .containsEntry("upstream_file_url", "https://files.example/file_123")
                .containsEntry("status", "completed")
                .containsEntry("upstream_status", 200)
                .containsEntry("response_id", "file_123");
    }

    @Test
    void upstreamFailureIsPersistedWithoutResponseBody() throws Exception {
        when(uploadClient.upload(any())).thenThrow(new UpstreamCallException(
                HttpStatus.BAD_GATEWAY, "upstream_http_503", true,
                "upstream_http_503", 503, null
        ));

        mockMvc.perform(multipart("/v1/files/upload")
                        .file(new MockMultipartFile("file", "failed.bin", "application/octet-stream", new byte[]{1, 2, 3}))
                        .header("X-Request-Id", "req_file_upload_failure_001"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("upstream_http_503"));

        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, upstream_status, error_code, error_summary, response_body
                  FROM file_upload_records
                 WHERE request_id = 'req_file_upload_failure_001'
                """))
                .containsEntry("status", "failed")
                .containsEntry("upstream_status", 503)
                .containsEntry("error_code", "upstream_http_503")
                .containsEntry("error_summary", "upstream_http_503")
                .containsEntry("response_body", null);
    }

    @Test
    void emptyFileIsRejectedBeforeCreatingRecord() throws Exception {
        mockMvc.perform(multipart("/v1/files/upload")
                        .file(new MockMultipartFile("file", "empty.txt", MediaType.TEXT_PLAIN_VALUE, new byte[0]))
                        .header("X-Request-Id", "req_file_upload_empty_001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM file_upload_records", Integer.class)).isZero();
    }
}
