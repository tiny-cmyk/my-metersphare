package io.metersphere.functional.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Notion 页面的子块信息（用于页面层级遍历）
 */
@Data
@AllArgsConstructor
public class NotionChildBlock {

    /** 块/页面/数据库的 ID */
    private String id;

    /** 标题/名称 */
    private String title;

    /**
     * 类型：
     * "child_page"     - 子页面（如 AI原始用例数据、正式用例库）
     * "child_database" - 子数据库（如 用户管理、录音管理）
     */
    private String type;
}
