package io.metersphere.functional.mapper;

import io.metersphere.functional.domain.NotionSyncConfig;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface NotionSyncConfigMapper {

    @Select("SELECT id, notion_page_id, project_id, enabled, create_time, update_time " +
            "FROM notion_sync_config WHERE notion_page_id = #{notionPageId} AND project_id = #{projectId}")
    NotionSyncConfig findByPageAndProject(@Param("notionPageId") String notionPageId,
                                          @Param("projectId") String projectId);

    @Select("SELECT id, notion_page_id, project_id, enabled, create_time, update_time " +
            "FROM notion_sync_config WHERE enabled = 1")
    List<NotionSyncConfig> findAllEnabled();

    @Insert("INSERT INTO notion_sync_config (id, notion_page_id, project_id, enabled, create_time, update_time) " +
            "VALUES(#{id}, #{notionPageId}, #{projectId}, #{enabled}, #{createTime}, #{updateTime})")
    int insert(NotionSyncConfig config);

    @Update("UPDATE notion_sync_config SET enabled = #{enabled}, update_time = #{updateTime} WHERE id = #{id}")
    int update(NotionSyncConfig config);
}
