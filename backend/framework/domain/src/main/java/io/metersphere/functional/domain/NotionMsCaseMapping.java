package io.metersphere.functional.domain;

import lombok.Data;

import java.io.Serializable;

@Data
public class NotionMsCaseMapping implements Serializable {

    private String notionPageId;

    private String msCaseId;

    private String projectId;

    private String notionDbId;

    /** Notion 返回的 last_edited_time（ISO8601），用于变更检测 */
    private String notionLastEdited;

    /** MeterSphere updateTime 毫秒时间戳，用于变更检测 */
    private Long msLastUpdated;

    /** 上次同步时 Notion 侧的标签列表（JSON 数组字符串），用于三方合并检测标签删除 */
    private String notionTags;

    private static final long serialVersionUID = 1L;
}
