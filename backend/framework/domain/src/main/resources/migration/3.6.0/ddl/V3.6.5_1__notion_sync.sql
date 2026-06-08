-- Notion <-> MeterSphere 双向同步
SET SESSION innodb_lock_wait_timeout = 7200;

-- 映射表：记录 Notion 行（用例）与 MS 用例 的对应关系
CREATE TABLE IF NOT EXISTS notion_ms_case_mapping (
    `notion_page_id`     VARCHAR(100) NOT NULL COMMENT 'Notion 行（页面）ID',
    `ms_case_id`         VARCHAR(50)  NOT NULL COMMENT 'MeterSphere 用例 ID',
    `project_id`         VARCHAR(50)  NOT NULL COMMENT '项目 ID',
    `notion_db_id`       VARCHAR(100) NOT NULL COMMENT '所属 Notion 数据库 ID',
    `notion_last_edited` VARCHAR(30)           COMMENT 'Notion 最后编辑时间（ISO8601）',
    `ms_last_updated`    BIGINT                COMMENT 'MeterSphere 最后更新时间（毫秒）',
    PRIMARY KEY (`notion_page_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = 'Notion 用例行与 MeterSphere 用例的映射表';

CREATE INDEX idx_nmsm_ms_case ON notion_ms_case_mapping (`ms_case_id`);
CREATE INDEX idx_nmsm_project_db ON notion_ms_case_mapping (`project_id`, `notion_db_id`);

-- 同步配置表：记录哪个 Notion 产品页面（web4.0/app4.0/desktop4.0）对应哪个 MS 项目
CREATE TABLE IF NOT EXISTS notion_sync_config (
    `id`               VARCHAR(50)  NOT NULL COMMENT 'ID',
    `notion_page_id`   VARCHAR(100) NOT NULL COMMENT 'Notion 产品页面 ID（如 web4.0 的页面 ID）',
    `project_id`       VARCHAR(50)  NOT NULL COMMENT 'MS 项目 ID',
    `enabled`          BIT          NOT NULL DEFAULT 1 COMMENT '是否启用',
    `create_time`      BIGINT       NOT NULL COMMENT '创建时间',
    `update_time`      BIGINT       NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY uk_nsc_page_project (`notion_page_id`, `project_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = 'Notion 同步配置表';
