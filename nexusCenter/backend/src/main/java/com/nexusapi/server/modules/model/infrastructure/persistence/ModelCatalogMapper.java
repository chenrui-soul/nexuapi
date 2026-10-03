package com.nexusapi.server.modules.model.infrastructure.persistence;

import com.nexusapi.server.modules.model.application.ModelCatalogItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

@Mapper
public interface ModelCatalogMapper {

    @Select("""
            <script>
            SELECT id,
                   public_name,
                   display_name,
                   provider,
                   capability_type,
                   context_window,
                   supports_streaming,
                   supports_tools,
                   input_price,
                   output_price,
                   price_unit
              FROM ai_models
             WHERE public_visible = true
               AND status = 'active'
               <if test="allowedModelIds != null and !allowedModelIds.isEmpty()">
                 AND id IN
                 <foreach collection="allowedModelIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               </if>
             ORDER BY capability_type, display_name
            </script>
            """)
    List<ModelCatalogItem> findPublicModels(@Param("allowedModelIds") List<UUID> allowedModelIds);
}
