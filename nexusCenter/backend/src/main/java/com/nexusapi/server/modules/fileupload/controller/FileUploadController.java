package com.nexusapi.server.modules.fileupload.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusapi.server.common.config.FileUploadProperties;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.fileupload.model.FileUploadRequest;
import com.nexusapi.server.modules.fileupload.service.FileUploadService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 独立文件上传公共入口；不要求平台 API Key，也不读取模型、分组或计费参数。 */
@RestController
@RequestMapping("/v1/files")
public class FileUploadController {
    private final FileUploadService service;
    private final FileUploadProperties properties;

    public FileUploadController(FileUploadService service, FileUploadProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping(
            value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<JsonNode> upload(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest servletRequest
    ) {
        FileUploadRequest upload = FileUploadRequest.from(file, properties);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.upload(upload, ClientRequestMetadata.from(servletRequest)));
    }
}
