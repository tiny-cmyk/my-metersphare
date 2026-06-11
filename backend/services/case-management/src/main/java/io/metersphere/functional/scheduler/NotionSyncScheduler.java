package io.metersphere.functional.scheduler;

import io.metersphere.functional.domain.NotionSyncConfig;
import io.metersphere.functional.mapper.NotionSyncConfigMapper;
import io.metersphere.functional.service.NotionSyncService;
import io.metersphere.sdk.util.LogUtils;
import jakarta.annotation.Resource;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Notion 定时同步任务
 *
 * ──────────────────────────────────────────────────────────────
 * 配置方式（在 /opt/metersphere/conf/metersphere.properties 中填写）：
 *
 *   integration.notion.token=ntn_你的token
 *
 *   # 格式：Notion页面ID:MS项目ID，多个用英文逗号分隔
 *   notion.sync.mappings=WEB_PAGE_ID:1465684991651422208,APP_PAGE_ID:1465686383220826112,DESKTOP_PAGE_ID:1465686572199387136
 *
 *   # 可选，同步间隔毫秒，默认5分钟
 *   notion.sync.interval-ms=300000
 * ──────────────────────────────────────────────────────────────
 *
 * WEB_PAGE_ID / APP_PAGE_ID / DESKTOP_PAGE_ID 是 web4.0、app4.0、desktop4.0
 * 这三个 Notion 页面的 ID（从页面 URL 中获取32位十六进制部分）。
 */
@Component
public class NotionSyncScheduler {

    private static final String SYSTEM_USER = "admin";

    /**
     * 格式：pageId1:projectId1,pageId2:projectId2,...
     * 例：abc123:1465684991651422208,def456:1465686383220826112
     */
    @Value("${notion.sync.mappings:}")
    private String syncMappings;

    @Resource
    private NotionSyncService notionSyncService;

    @Resource
    private NotionSyncConfigMapper syncConfigMapper;

    /**
     * 应用完全就绪后：解析 notion.sync.mappings 配置，异步执行一次全量同步
     * 使用 ApplicationReadyEvent 而非 @PostConstruct，确保所有 MyBatis XML fragments 已加载
     *
     * NOTE: Notion→MS 同步已停用，不再需要此功能（用例现在由 AI Agent 直接写入 MeterSphere）
     */
    @Async
    // @EventListener(ApplicationReadyEvent.class)  // 已停用
    public void initSync() {
        List<String[]> mappings = parseMappings();
        if (mappings.isEmpty()) {
            LogUtils.info("[Notion同步] 未配置 notion.sync.mappings，跳过同步");
            return;
        }

        for (String[] entry : mappings) {
            String notionPageId = entry[0];
            String msProjectId = entry[1];
            try {
                notionSyncService.syncProductPage(notionPageId, msProjectId, SYSTEM_USER);
                LogUtils.info("[Notion同步] 启动后首次同步完成：Notion页面={} → 项目={}", notionPageId, msProjectId);
            } catch (Exception e) {
                LogUtils.error("[Notion同步] 启动后同步失败：Notion页面=" + notionPageId + " 项目=" + msProjectId, e);
            }
        }
    }

    /**
     * 定时任务：按 notion.sync.interval-ms 间隔（默认5分钟）自动同步
     * 同步对象：notion_sync_config 表中所有 enabled=1 的配置
     *
     * NOTE: Notion→MS 同步已停用（用例现在由 AI Agent 直接写入 MeterSphere）
     */
    // @Scheduled(fixedDelayString = "${notion.sync.interval-ms:300000}")  // 已停用
    public void scheduledSync() {
        List<NotionSyncConfig> configs = syncConfigMapper.findAllEnabled();
        if (CollectionUtils.isEmpty(configs)) {
            return;
        }

        for (NotionSyncConfig config : configs) {
            try {
                notionSyncService.syncProductPage(
                        config.getNotionPageId(), config.getProjectId(), SYSTEM_USER);
                LogUtils.info("[Notion同步] 定时同步完成：Notion页面={} → 项目={}",
                        config.getNotionPageId(), config.getProjectId());
            } catch (Exception e) {
                LogUtils.error("[Notion同步] 定时同步失败：Notion页面=" + config.getNotionPageId() + " 项目=" + config.getProjectId(), e);
            }
        }
    }

    /**
     * 解析 notion.sync.mappings 配置字符串
     * 格式：pageId1:projectId1,pageId2:projectId2
     */
    private List<String[]> parseMappings() {
        List<String[]> result = new ArrayList<>();
        if (StringUtils.isBlank(syncMappings)) return result;

        for (String entry : syncMappings.split(",")) {
            String[] parts = entry.trim().split(":");
            if (parts.length == 2
                    && StringUtils.isNotBlank(parts[0])
                    && StringUtils.isNotBlank(parts[1])) {
                result.add(new String[]{parts[0].trim(), parts[1].trim()});
            }
        }
        return result;
    }
}
