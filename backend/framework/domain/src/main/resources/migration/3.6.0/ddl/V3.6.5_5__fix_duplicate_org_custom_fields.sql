-- Fix: org-level custom_field has duplicates (same scope_id+scene+name) causing
-- "自定义字段已存在" on project creation.
-- For each duplicate group, keep the MIN(id) row and delete the others.

-- Step 1: Delete custom_field_option rows for duplicate fields
DELETE FROM custom_field_option
WHERE field_id IN (
    SELECT id FROM (
        SELECT cf.id
        FROM custom_field cf
        INNER JOIN (
            SELECT scope_id, scene, name, MIN(id) AS keep_id
            FROM custom_field
            WHERE scope_type = 'ORGANIZATION'
            GROUP BY scope_id, scene, name
            HAVING COUNT(*) > 1
        ) AS dups
          ON cf.scope_id = dups.scope_id
         AND cf.scene    = dups.scene
         AND cf.name     = dups.name
         AND cf.id      != dups.keep_id
        WHERE cf.scope_type = 'ORGANIZATION'
    ) AS to_del
);

-- Step 2: Delete the duplicate custom_field rows
DELETE FROM custom_field
WHERE id IN (
    SELECT id FROM (
        SELECT cf.id
        FROM custom_field cf
        INNER JOIN (
            SELECT scope_id, scene, name, MIN(id) AS keep_id
            FROM custom_field
            WHERE scope_type = 'ORGANIZATION'
            GROUP BY scope_id, scene, name
            HAVING COUNT(*) > 1
        ) AS dups
          ON cf.scope_id = dups.scope_id
         AND cf.scene    = dups.scene
         AND cf.name     = dups.name
         AND cf.id      != dups.keep_id
        WHERE cf.scope_type = 'ORGANIZATION'
    ) AS to_del
);
