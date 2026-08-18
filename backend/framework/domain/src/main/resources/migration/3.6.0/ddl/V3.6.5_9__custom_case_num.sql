-- 功能用例模块表增加用例编号前缀字段
ALTER TABLE functional_case_module ADD COLUMN case_prefix VARCHAR(100) DEFAULT NULL COMMENT '用例编号前缀';

-- 功能用例表增加自定义编号字段
ALTER TABLE functional_case ADD COLUMN custom_num VARCHAR(255) DEFAULT NULL COMMENT '自定义用例编号';
CREATE INDEX idx_custom_num ON functional_case (custom_num);
