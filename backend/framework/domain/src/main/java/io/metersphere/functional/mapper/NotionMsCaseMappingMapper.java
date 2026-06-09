package io.metersphere.functional.mapper;

import io.metersphere.functional.domain.NotionMsCaseMapping;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface NotionMsCaseMappingMapper {

    @Select("SELECT notion_page_id, ms_case_id, project_id, notion_db_id, notion_last_edited, ms_last_updated, notion_tags " +
            "FROM notion_ms_case_mapping WHERE notion_page_id = #{notionPageId}")
    NotionMsCaseMapping findByNotionPageId(@Param("notionPageId") String notionPageId);

    @Select("SELECT notion_page_id, ms_case_id, project_id, notion_db_id, notion_last_edited, ms_last_updated, notion_tags " +
            "FROM notion_ms_case_mapping WHERE ms_case_id = #{msCaseId}")
    NotionMsCaseMapping findByMsCaseId(@Param("msCaseId") String msCaseId);

    @Select("SELECT notion_page_id, ms_case_id, project_id, notion_db_id, notion_last_edited, ms_last_updated, notion_tags " +
            "FROM notion_ms_case_mapping WHERE project_id = #{projectId} AND notion_db_id = #{notionDbId}")
    List<NotionMsCaseMapping> findByProjectAndDb(@Param("projectId") String projectId,
                                                  @Param("notionDbId") String notionDbId);

    @Insert("INSERT INTO notion_ms_case_mapping " +
            "(notion_page_id, ms_case_id, project_id, notion_db_id, notion_last_edited, ms_last_updated, notion_tags) " +
            "VALUES(#{notionPageId}, #{msCaseId}, #{projectId}, #{notionDbId}, #{notionLastEdited}, #{msLastUpdated}, #{notionTags}) " +
            "ON DUPLICATE KEY UPDATE " +
            "notion_db_id = VALUES(notion_db_id), notion_last_edited = VALUES(notion_last_edited), " +
            "ms_last_updated = VALUES(ms_last_updated), notion_tags = VALUES(notion_tags)")
    int insert(NotionMsCaseMapping mapping);

    @Update("UPDATE notion_ms_case_mapping " +
            "SET notion_last_edited = #{notionLastEdited}, ms_last_updated = #{msLastUpdated}, notion_tags = #{notionTags} " +
            "WHERE notion_page_id = #{notionPageId}")
    int updateSyncTime(NotionMsCaseMapping mapping);

    @Delete("DELETE FROM notion_ms_case_mapping WHERE notion_page_id = #{notionPageId}")
    int deleteByNotionPageId(@Param("notionPageId") String notionPageId);

    @Delete("DELETE FROM notion_ms_case_mapping WHERE ms_case_id = #{msCaseId}")
    int deleteByMsCaseId(@Param("msCaseId") String msCaseId);
}
