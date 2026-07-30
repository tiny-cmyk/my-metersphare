-- 为所有现有组织和项目添加"是否自动化"内置字段
-- 1. 为每个组织创建字段
INSERT INTO custom_field (id, name, scene, `type`, remark, internal, scope_type, create_time, update_time, create_user, scope_id, enable_option_key)
SELECT UUID_SHORT(), 'automation_status', 'FUNCTIONAL', 'SELECT', '', 1, 'ORGANIZATION',
       UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'admin', o.id, 0
FROM organization o
WHERE NOT EXISTS (
    SELECT 1 FROM custom_field cf WHERE cf.name = 'automation_status' AND cf.scope_id = o.id
);

-- 2. 为每个组织级字段插入选项
INSERT INTO custom_field_option (field_id, value, `text`, internal, pos)
SELECT cf.id, v.value, v.text, 1, v.pos
FROM custom_field cf
CROSS JOIN (
    SELECT 'automatable' AS value, '可自动化' AS text, 1 AS pos UNION ALL
    SELECT 'automated', '已自动化', 2 UNION ALL
    SELECT 'manual', '需手工', 3 UNION ALL
    SELECT 'to_be_confirmed', '待确认', 4 UNION ALL
    SELECT 'not_applicable', '不涉及', 5
) v
WHERE cf.name = 'automation_status' AND cf.scope_type = 'ORGANIZATION'
AND NOT EXISTS (
    SELECT 1 FROM custom_field_option cfo WHERE cfo.field_id = cf.id AND cfo.value = v.value
);

-- 3. 为每个组织的默认功能模板关联字段
INSERT INTO template_custom_field (id, field_id, template_id, required, pos, system_field, api_field_id, default_value)
SELECT UUID_SHORT(), cf.id, t.id, 0, 1, 0, NULL, NULL
FROM custom_field cf
JOIN template t ON t.scope_id = cf.scope_id AND t.scene = 'FUNCTIONAL' AND t.internal = 1
WHERE cf.name = 'automation_status' AND cf.scope_type = 'ORGANIZATION'
AND NOT EXISTS (
    SELECT 1 FROM template_custom_field tcf WHERE tcf.field_id = cf.id AND tcf.template_id = t.id
);

-- 4. 为每个项目创建字段（ref_id 指向组织级字段）
INSERT INTO custom_field (id, name, scene, `type`, remark, internal, scope_type, create_time, update_time, create_user, scope_id, ref_id, enable_option_key)
SELECT UUID_SHORT(), 'automation_status', 'FUNCTIONAL', 'SELECT', '', 1, 'PROJECT',
       UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 'admin', p.id,
       (SELECT cf2.id FROM custom_field cf2 WHERE cf2.name = 'automation_status' AND cf2.scope_id = p.organization_id AND cf2.scope_type = 'ORGANIZATION' LIMIT 1),
       0
FROM project p
WHERE NOT EXISTS (
    SELECT 1 FROM custom_field cf WHERE cf.name = 'automation_status' AND cf.scope_id = p.id
);

-- 5. 为每个项目级字段插入选项
INSERT INTO custom_field_option (field_id, value, `text`, internal, pos)
SELECT cf.id, v.value, v.text, 1, v.pos
FROM custom_field cf
CROSS JOIN (
    SELECT 'automatable' AS value, '可自动化' AS text, 1 AS pos UNION ALL
    SELECT 'automated', '已自动化', 2 UNION ALL
    SELECT 'manual', '需手工', 3 UNION ALL
    SELECT 'to_be_confirmed', '待确认', 4 UNION ALL
    SELECT 'not_applicable', '不涉及', 5
) v
WHERE cf.name = 'automation_status' AND cf.scope_type = 'PROJECT'
AND NOT EXISTS (
    SELECT 1 FROM custom_field_option cfo WHERE cfo.field_id = cf.id AND cfo.value = v.value
);

-- 6. 为每个项目的默认功能模板关联字段
INSERT INTO template_custom_field (id, field_id, template_id, required, pos, system_field, api_field_id, default_value)
SELECT UUID_SHORT(), cf.id, t.id, 0, 1, 0, NULL, NULL
FROM custom_field cf
JOIN template t ON t.scope_id = cf.scope_id AND t.scene = 'FUNCTIONAL' AND t.internal = 1
WHERE cf.name = 'automation_status' AND cf.scope_type = 'PROJECT'
AND NOT EXISTS (
    SELECT 1 FROM template_custom_field tcf WHERE tcf.field_id = cf.id AND tcf.template_id = t.id
);
