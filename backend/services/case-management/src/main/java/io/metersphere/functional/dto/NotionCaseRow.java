package io.metersphere.functional.dto;

import lombok.Data;

import java.util.List;

/**
 * 表示从 Notion 数据库中读取的一行用例数据
 */
@Data
public class NotionCaseRow {

    /** Notion 页面 ID */
    private String pageId;

    /** 用例名称（对应 Notion "用例名称" title 字段）*/
    private String name;

    /** 优先级：P0/P1/P2/P3（对应 Notion "优先级" select 字段）*/
    private String priority;

    /** 前置条件（对应 Notion "前置条件" rich_text 字段）*/
    private String prerequisite;

    /** 测试步骤（对应 Notion "测试步骤" rich_text 字段）*/
    private String steps;

    /** 预期结果（对应 Notion "预期结果" rich_text 字段）*/
    private String expectedResult;

    /** 备注（对应 Notion "备注" rich_text 字段）*/
    private String description;

    /** 模块路径（对应 Notion "模块路径" rich_text 字段）*/
    private String modulePath;

    /** 标签（对应 Notion "标签" multi_select 字段）*/
    private List<String> tags;

    /** Notion 最后编辑时间，ISO8601 格式，用于变更检测 */
    private String lastEditedTime;

    /** 创建人 MS 用户名（对应 Notion "创建人" rich_text 字段）*/
    private String creatorName;

    /** 是否已在 Notion 中归档（archived=true 表示已删除）*/
    private boolean archived;
}
