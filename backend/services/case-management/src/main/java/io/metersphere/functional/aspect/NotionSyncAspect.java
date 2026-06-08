package io.metersphere.functional.aspect;

import io.metersphere.functional.domain.FunctionalCase;
import io.metersphere.functional.request.FunctionalCaseDeleteRequest;
import io.metersphere.functional.service.NotionSyncService;
import io.metersphere.sdk.util.LogUtils;
import jakarta.annotation.Resource;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * MeterSphere → Notion 反向同步切面
 *
 * 监听用例的更新和删除操作，异步推送变更到 Notion。
 * 当操作来源本身就是 Notion 同步（isSyncingFromNotion()==true）时跳过，防止双向循环。
 */
@Aspect
@Component
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
