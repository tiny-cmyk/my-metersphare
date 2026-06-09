-- Add notion_tags column to track last-synced Notion tags for 3-way merge
ALTER TABLE notion_ms_case_mapping
    ADD COLUMN notion_tags TEXT NULL COMMENT 'Last synced Notion tags (JSON array), used for 3-way merge to detect tag deletions';
