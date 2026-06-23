-- Add automation_steps column to functional_case_blob
ALTER TABLE functional_case_blob ADD COLUMN IF NOT EXISTS `automation_steps` LONGBLOB COMMENT '自动化步骤';
