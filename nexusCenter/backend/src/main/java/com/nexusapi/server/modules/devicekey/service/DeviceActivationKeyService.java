package com.nexusapi.server.modules.devicekey.service;

import com.nexusapi.server.common.crypto.AesGcmFieldCipher;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.admin.service.AdminAuditService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.devicekey.dto.*;
import com.nexusapi.server.modules.devicekey.entity.DeviceActivationKeyRow;
import com.nexusapi.server.modules.devicekey.mapper.DeviceActivationKeyMapper;
import com.nexusapi.server.modules.devicekey.support.DeviceActivationKeyGenerator;
import com.nexusapi.server.modules.devicekey.vo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DeviceActivationKeyService {
    private final DeviceActivationKeyMapper mapper; private final DeviceActivationKeyGenerator generator; private final AesGcmFieldCipher cipher; private final AdminAuditService audit;
    public DeviceActivationKeyService(DeviceActivationKeyMapper mapper, DeviceActivationKeyGenerator generator, AesGcmFieldCipher cipher, AdminAuditService audit){this.mapper=mapper;this.generator=generator;this.cipher=cipher;this.audit=audit;}
    @Transactional public PageResponse<DeviceActivationKeyItemResponse> list(int page, int pageSize, String query, UUID actor, ClientRequestMetadata meta){
        String normalizedQuery = normalizeQuery(query);
        int offset = Math.multiplyExact(page - 1, pageSize);
        List<DeviceActivationKeyItemResponse> result = mapper.findPage(normalizedQuery, offset, pageSize).stream().map(this::response).toList();
        result.forEach(item -> audit.record(actor,"device_activation_key.reveal","device_activation_key",item.id(),null,Map.of("id",item.id().toString()),meta));
        return new PageResponse<>(result, mapper.countPage(normalizedQuery), page, pageSize);
    }
    @Transactional public DeviceActivationKeyCreated create(DeviceActivationKeyCreateRequest req, UUID actor, ClientRequestMetadata meta){
        String name=req.name().strip(), app=req.applicationCode().strip(); if(name.isBlank()||app.isBlank()) throw invalid("名称和应用编码不能为空");
        if(req.expiresAt()!=null&&!req.expiresAt().isAfter(Instant.now())) throw invalid("有效期必须晚于当前时间");
        var g=generator.generate(); UUID id=UUID.randomUUID(); mapper.insert(id,name,app,g.prefix(),g.suffix(),cipher.encrypt(g.secret()),g.hash(),req.expiresAt());
        var item=response(mapper.findById(id)); audit.record(actor,"device_activation_key.create","device_activation_key",id,null,Map.of("name",name,"applicationCode",app),meta); return new DeviceActivationKeyCreated(item,g.secret());
    }
    @Transactional public DeviceActivationKeyItemResponse changeStatus(UUID id, DeviceActivationKeyStatusRequest req, UUID actor, ClientRequestMetadata meta){
        if(!req.status().equals("active")&&!req.status().equals("disabled")) throw invalid("状态只支持 active 或 disabled");
        var before=mapper.findById(id); if(before==null) throw notFound(); if(mapper.updateStatus(id,req.status(),req.version())!=1) throw invalid("密钥已被其他操作修改，请刷新后重试");
        var after=response(mapper.findById(id)); audit.record(actor,"device_activation_key.status."+req.status(),"device_activation_key",id,Map.of("status",before.getStatus()),Map.of("status",after.status()),meta); return after;
    }
    @Transactional public boolean delete(UUID id, UUID actor, ClientRequestMetadata meta){
        var before = mapper.findById(id); if(before == null) throw notFound();
        if("deleted".equals(before.getStatus())) return false;
        if(mapper.markDeleted(id) != 1) return false;
        audit.record(actor,"device_activation_key.delete","device_activation_key",id,Map.of("status",before.getStatus()),Map.of("status","deleted"),meta);
        return true;
    }
    @Transactional public DeviceActivationVerifyResponse verify(DeviceActivationVerifyRequest req){
        var row=mapper.findBySecretHash(generator.digest(req.secret())); if(row==null) return new DeviceActivationVerifyResponse(false,"invalid",null,null,null);
        if(!"active".equals(row.getStatus())||(row.getExpiresAt()!=null&&!row.getExpiresAt().isAfter(Instant.now()))) return new DeviceActivationVerifyResponse(false,effective(row),row.getApplicationCode(),row.getExpiresAt(),row.getActivatedAt());
        byte[] deviceHash=generator.digest(req.deviceCode());
        if(row.getDeviceCodeHash()==null){if(mapper.bindDevice(row.getId(),deviceHash)!=1){row=mapper.findBySecretHash(generator.digest(req.secret()));} else {row=mapper.findById(row.getId());}}
        if(row.getDeviceCodeHash()==null||!MessageDigest.isEqual(row.getDeviceCodeHash(),deviceHash)) return new DeviceActivationVerifyResponse(false,"device_bound",row.getApplicationCode(),row.getExpiresAt(),row.getActivatedAt());
        mapper.touchVerified(row.getId()); return new DeviceActivationVerifyResponse(true,"active",row.getApplicationCode(),row.getExpiresAt(),row.getActivatedAt());
    }
    private DeviceActivationKeyItemResponse response(DeviceActivationKeyRow r){return new DeviceActivationKeyItemResponse(r.getId(),r.getName(),r.getApplicationCode(),r.getKeyPrefix()+"..."+r.getKeySuffix(),cipher.decrypt(r.getEncryptedSecret()),effective(r),r.getDeviceCodeHash()!=null,r.getActivatedAt(),r.getExpiresAt(),r.getLastVerifiedAt(),r.getCreatedAt(),r.getVersion());}
    private String effective(DeviceActivationKeyRow r){return "active".equals(r.getStatus())&&r.getExpiresAt()!=null&&!r.getExpiresAt().isAfter(Instant.now())?"expired":r.getStatus();}
    private String normalizeQuery(String value){
        if(value == null || value.isBlank()) return null;
        String result = value.strip();
        if(result.length() > 120 || result.codePoints().anyMatch(Character::isISOControl)) throw invalid("搜索内容格式无效");
        return result;
    }
    private BusinessException invalid(String msg){return new BusinessException(ErrorCode.VALIDATION_ERROR,msg,null);} private BusinessException notFound(){return new BusinessException(ErrorCode.VALIDATION_ERROR,"设备激活密钥不存在",null);}
    public record DeviceActivationKeyCreated(DeviceActivationKeyItemResponse key,String secret){}
}
