commit 3675836230620378e8e44cd411945730f3c441c2
Author: Tiny <tiny@plaud.ai>
Date:   Fri May 15 11:17:58 2026 +0800

    fix: add missing chatModelId and organizationId to notionSave call

diff --git a/frontend/src/views/case-management/caseManagementFeature/components/caseTable.vue b/frontend/src/views/case-management/caseManagementFeature/components/caseTable.vue
index 868e621c5a..13319e21ae 100644
--- a/frontend/src/views/case-management/caseManagementFeature/components/caseTable.vue
+++ b/frontend/src/views/case-management/caseManagementFeature/components/caseTable.vue
@@ -662,6 +662,8 @@
     try {
       await notionSave({
         prompt: notionPreviewContent.value,
+        chatModelId: '',
+        organizationId: appStore.currentOrgId || '',
         projectId: currentProjectId.value,
         moduleId: props.activeFolder,
         templateId: templateId.value,
