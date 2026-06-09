-- Fix: org-level templates have duplicates (same scope_id+scene+name) causing
-- "模板已存在" on project creation.
-- For each duplicate group, keep the MIN(id) row and delete the others.

-- Step 1: Delete template_custom_field rows for duplicate templates
DELETE FROM template_custom_field
WHERE template_id IN (
    SELECT id FROM (
        SELECT t.id
        FROM template t
        INNER JOIN (
            SELECT scope_id, scene, name, MIN(id) AS keep_id
            FROM template
            WHERE scope_type = 'ORGANIZATION'
            GROUP BY scope_id, scene, name
            HAVING COUNT(*) > 1
        ) AS dups
          ON t.scope_id = dups.scope_id
         AND t.scene    = dups.scene
         AND t.name     = dups.name
         AND t.id      != dups.keep_id
        WHERE t.scope_type = 'ORGANIZATION'
    ) AS to_del
);

-- Step 2: Delete the duplicate template rows themselves
DELETE FROM template
WHERE id IN (
    SELECT id FROM (
        SELECT t.id
        FROM template t
        INNER JOIN (
            SELECT scope_id, scene, name, MIN(id) AS keep_id
            FROM template
            WHERE scope_type = 'ORGANIZATION'
            GROUP BY scope_id, scene, name
            HAVING COUNT(*) > 1
        ) AS dups
          ON t.scope_id = dups.scope_id
         AND t.scene    = dups.scene
         AND t.name     = dups.name
         AND t.id      != dups.keep_id
        WHERE t.scope_type = 'ORGANIZATION'
    ) AS to_del
);
