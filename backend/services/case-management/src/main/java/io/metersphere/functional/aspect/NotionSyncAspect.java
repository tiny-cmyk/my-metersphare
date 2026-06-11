package io.metersphere.functional.aspect;

import io.metersphere.functional.domain.FunctionalCase;
import io.metersphere.functional.request.FunctionalCaseBatchEditRequest;
import io.metersphere.functional.request.FunctionalCaseDeleteRequest;
import io.metersphere.functional.service.NotionSyncService;
import org.apache.commons.collections4.CollectionUtils;
import io.metersphere.sdk.util.LogUtils;
import jakarta.annotation.Resource;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * MeterSphere → Notion 反向同步切面
 *
 * NOTE: 已停用 — 用例现在由 AI Agent 直接写入 MeterSphere，不再需要双向同步 Notion。
 * 若需恢复，取消注释 @Aspect 和 @Component 即可。
 */
// @Aspect   // 已停用
// @Component  // 已停用
public class NotionSyncAspect {

    @Resource
    private NotionSyncService notionSyncService;

    /**
     * 拦截 FunctionalCaseService.updateFunctionalCase()
     * 用例更新后，将变更异步推送到 Notion
     */
    @AfterReturning(
            pointcut = "execution(* io.metersphere.functional.service.FunctionalCaseService.updateFunctionalCase(..))",
            returning = "result"
    )
    public void afterCaseUpdate(JoinPoint jp, Object result) {
        if (NotionSyncService.isSyncingFromNotion()) {
            return;
        }
        if (!(result instanceof FunctionalCase updatedCase)) {
            return;
        }
        try {
            notionSyncService.asyncPushCaseToNotion(updatedCase.getId());
        } catch (Exception e) {
            LogUtils.error("[Notion同步] 推送用例更新到 Notion 失败，caseId={}: {}", updatedCase.getId(), e.getMessage());
        }
    }

    /**
     * 拦截 FunctionalCaseService.batchEditFunctionalCase()
     * 批量打标签时，将每条涉及的用例推送到 Notion
     */
    @AfterReturning(
            pointcut = "execution(* io.metersphere.functional.service.FunctionalCaseService.batchEditFunctionalCase(..))"
    )
    public void afterBatchCaseEdit(JoinPoint jp) {
        if (NotionSyncService.isSyncingFromNotion()) {
            return;
        }
        Object[] args = jp.getArgs();
        if (args == null || args.length == 0 || !(args[0] instanceof FunctionalCaseBatchEditRequest request)) {
            return;
        }
        // 只有标签有变化时才推送（tags非空 或 clear=true）
        if (CollectionUtils.isEmpty(request.getTags()) && !request.isClear()) {
            return;
        }
        try {
            notionSyncService.asyncBatchPushTagChangesToNotion(request);
        } catch (Exception e) {
            LogUtils.error("[Notion同步] 触发批量推送到 Notion 失败: {}", e.getMessage());
        }
    }

    /**
     * 拦截 FunctionalCaseService.deleteFunctionalCase()
     * 用例删除后，将对应 Notion 页面归档
     */
    @AfterReturning(
            pointcut = "execution(* io.metersphere.functional.service.FunctionalCaseService.deleteFunctionalCase(..))"
    )
    public void afterCaseDelete(JoinPoint jp) {
        if (NotionSyncService.isSyncingFromNotion()) {
            return;
        }
        Object[] args = jp.getArgs();
        if (args == null || args.length == 0 || !(args[0] instanceof FunctionalCaseDeleteRequest req)) {
            return;
        }
        try {
            notionSyncService.asyncArchiveNotionCase(req.getId());
        } catch (Exception e) {
            LogUtils.error("[Notion同步] 归档 Notion 页面失败，caseId={}: {}", req.getId(), e.getMessage());
        }
    }
}
