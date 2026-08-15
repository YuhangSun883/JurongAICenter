package com.jurong.aicenter.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jurong.aicenter.entity.MediaLibrary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MediaLibraryRepository extends BaseMapper<MediaLibrary> {

    /**
     * 2026-08-15 V19: 判断某库是否有子库（未软删）
     */
    @Select("SELECT EXISTS(SELECT 1 FROM media_libraries WHERE parent_id = #{parentId} AND deleted = 0)")
    boolean hasChildren(@Param("parentId") Long parentId);

    /**
     * 2026-08-15 V19: 列出某父库下所有未软删子库
     */
    @Select("SELECT * FROM media_libraries WHERE parent_id = #{parentId} AND deleted = 0 ORDER BY sort_order ASC, id ASC")
    List<MediaLibrary> listChildren(@Param("parentId") Long parentId);

    /**
     * 2026-08-15 V19: 列出某用户的所有根库（parent_id IS NULL 且未软删）
     */
    @Select("SELECT * FROM media_libraries WHERE user_id = #{userId} AND parent_id IS NULL AND deleted = 0 ORDER BY sort_order ASC, id ASC")
    List<MediaLibrary> listRoots(@Param("userId") Long userId);

    /**
     * 2026-08-15 V19: 取某库所有后代 id（含自己），用于级联删除
     * 用 MySQL 8 递归 CTE。注意：被软删的不算（已删的就没有后代的 active 行）。
     */
    @Select(value = """
        WITH RECURSIVE descendants AS (
          SELECT id, parent_id, name
          FROM media_libraries
          WHERE id = #{rootId} AND deleted = 0
          UNION ALL
          SELECT m.id, m.parent_id, m.name
          FROM media_libraries m
          INNER JOIN descendants d ON m.parent_id = d.id
          WHERE m.deleted = 0
        )
        SELECT id FROM descendants
        """)
    List<Long> listDescendantIds(@Param("rootId") Long rootId);

    /**
     * 2026-08-15 V19: 取某库所有祖先链路（root → ... → 自己），用于面包屑
     * 同样用 MySQL 8 递归 CTE。
     */
    @Select(value = """
        WITH RECURSIVE ancestors AS (
          SELECT id, parent_id, name, biz_type, type, 0 AS depth
          FROM media_libraries
          WHERE id = #{leafId} AND deleted = 0
          UNION ALL
          SELECT m.id, m.parent_id, m.name, m.biz_type, m.type, a.depth + 1
          FROM media_libraries m
          INNER JOIN ancestors a ON m.id = a.parent_id
          WHERE m.deleted = 0
        )
        SELECT id, parent_id AS parentId, name, biz_type AS bizType, type, depth
        FROM ancestors
        ORDER BY depth DESC
        """)
    List<MediaLibrary> listAncestors(@Param("leafId") Long leafId);
}
