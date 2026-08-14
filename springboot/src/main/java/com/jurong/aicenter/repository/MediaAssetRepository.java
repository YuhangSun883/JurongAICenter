package com.jurong.aicenter.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jurong.aicenter.entity.MediaAsset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MediaAssetRepository extends BaseMapper<MediaAsset> {
    @Select("""
        <script>
        SELECT
          id, user_id, library_id, type, source, name, mime_type, size_bytes,
          width, height, duration_sec, object_key, source_tool, source_task_id,
          deleted, created_at, updated_at
        FROM media_assets
        WHERE deleted = #{deleted}
        <if test="keyword != null and keyword != ''">
          AND (
            name LIKE CONCAT('%', #{keyword}, '%')
            OR source_tool LIKE CONCAT('%', #{keyword}, '%')
            OR source_task_id LIKE CONCAT('%', #{keyword}, '%')
            OR CAST(id AS CHAR) = #{keyword}
          )
        </if>
        <if test="type != null and type != ''">
          AND type = #{type}
        </if>
        <if test="source != null and source != ''">
          AND source = #{source}
        </if>
        ORDER BY created_at DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<MediaAsset> selectConsoleAssets(
        @Param("keyword") String keyword,
        @Param("type") String type,
        @Param("source") String source,
        @Param("deleted") int deleted,
        @Param("offset") int offset,
        @Param("limit") int limit
    );

    @Select("""
        <script>
        SELECT COUNT(1)
        FROM media_assets
        WHERE deleted = #{deleted}
        <if test="keyword != null and keyword != ''">
          AND (
            name LIKE CONCAT('%', #{keyword}, '%')
            OR source_tool LIKE CONCAT('%', #{keyword}, '%')
            OR source_task_id LIKE CONCAT('%', #{keyword}, '%')
            OR CAST(id AS CHAR) = #{keyword}
          )
        </if>
        <if test="type != null and type != ''">
          AND type = #{type}
        </if>
        <if test="source != null and source != ''">
          AND source = #{source}
        </if>
        </script>
        """)
    long countConsoleAssets(
        @Param("keyword") String keyword,
        @Param("type") String type,
        @Param("source") String source,
        @Param("deleted") int deleted
    );

    @Update("UPDATE media_assets SET deleted = 0, updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND deleted = 1")
    int restoreConsoleAsset(@Param("id") Long id);
}
