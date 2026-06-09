-- Fix: org-level 'functional_priority' custom field was duplicated, causing
-- "自定义字段已存在" error on project creation.
-- Remove the duplicate entry (keep original 101899920247619807, delete 16458984260603216095).
DELETE FROM custom_field_option WHERE field_id = '16458984260603216095';
DELETE FROM custom_field WHERE id = '16458984260603216095';
