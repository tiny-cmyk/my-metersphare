package io.metersphere.functional.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.metersphere.functional.request.NotionImportRequest;
import io.metersphere.sdk.exception.MSException;
import io.metersphere.system.dto.sdk.OptionDTO;
import io.metersphere.system.dto.request.ai.AIChatOption;
import io.metersphere.system.dto.request.ai.AIChatRequest;
import io.metersphere.system.dto.request.ai.AiModelSourceDTO;
import io.metersphere.system.service.AiChatBaseService;
import io.metersphere.system.service.SystemAIConfigService;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * Notion 集成服务
 * 支持从 Notion 页面 URL 读取内容并通过 AI 生成测试用例
 */
@Service
public class NotionService {

    private static final String NOTION_API_BASE = "https://api.notion.com/v1";
    private static final String NOTION_VERSION = "2022-06-28";

    @Value("${integration.notion.token:}")
    private String notionToken;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Resource
    private AiChatBaseService aiChatBaseService;

    @Resource
    private SystemAIConfigService systemAIConfigService;

    @Resource
    private FunctionalCaseAIService functionalCaseAIService;

    /**
     * 从 Notion 页面 URL 生成测试用例 (Markdown 格式，供前端展示确认)
     *
     * @param notionUrl Notion 页面 URL
     * @param userId    操作用户 ID
     * @return AI 生成的测试用例 Markdown 文本
     */
    public String generateCasesFromNotionUrl(NotionImportRequest request, String userId) {
        String notionUrl = request.getNotionUrl();
        String pageId = extractPageId(notionUrl);
        if (StringUtils.isBlank(pageId)) {
            throw new MSException("无法从 URL 中解析 Notion 页面 ID，请检查链接格式");
        }

        String pageTitle = fetchPageTitle(pageId);
        String pageContent = fetchPageContent(pageId);

        if (StringUtils.isBlank(pageContent)) {
            throw new MSException("Notion 页面内容为空，无法生成测试用例");
        }

        return callAiToGenerateCases(pageTitle, pageContent, userId,
                request.getChatModelId(), request.getOrganizationId());
    }

    /**
     * 从 Notion URL 中提取页面 ID
     * 支持格式：
     *   https://www.notion.so/Page-Title-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
     *   https://www.notion.so/workspace/xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
     *   https://notion.so/xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
     */
    private String extractPageId(String url) {
        if (StringUtils.isBlank(url)) return null;
        // 去掉 query string 和 fragment
        String cleaned = url.split("[?#]")[0];
        // 最后一段路径
        String[] parts = cleaned.split("/");
        String lastPart = parts[parts.length - 1];

        // Notion 页面 ID 是 32 位十六进制（带或不带连字符）
        // 格式1: Page-Title-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx (末尾32位hex)
        if (lastPart.length() >= 32) {
            String candidate = lastPart.replaceAll("-", "");
            // 取最后32位
            if (candidate.length() >= 32) {
                String hex = candidate.substring(candidate.length() - 32);
                if (hex.matches("[a-fA-F0-9]{32}")) {
                    // 格式化为带连字符的 UUID 格式
                    return formatAsUUID(hex);
                }
            }
        }

        // 格式2: 纯 UUID 格式 xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
        if (lastPart.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) {
            return lastPart;
        }

        return null;
    }

    private String formatAsUUID(String hex) {
        return hex.substring(0, 8) + "-" +
                hex.substring(8, 12) + "-" +
                hex.substring(12, 16) + "-" +
                hex.substring(16, 20) + "-" +
                hex.substring(20);
    }

    /**
     * 获取 Notion 页面标题
     */
    private String fetchPageTitle(String pageId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(NOTION_API_BASE + "/pages/" + pageId))
                    .header("Authorization", "Bearer " + notionToken)
                    .header("Notion-Version", NOTION_VERSION)
                    .header("Content-Type", "application/json")
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return "未知页面";
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode properties = root.path("properties");
            // 尝试从 title 或 Name 属性获取标题
            for (String titleKey : new String[]{"title", "Title", "Name", "名称"}) {
                JsonNode titleNode = properties.path(titleKey);
                if (!titleNode.isMissingNode()) {
                    JsonNode titleArray = titleNode.path("title");
                    if (titleArray.isArray() && !titleArray.isEmpty()) {
                        return titleArray.get(0).path("plain_text").asText("未知页面");
                    }
                }
            }
            return "未知页面";
        } catch (Exception e) {
            return "未知页面";
        }
    }

    /**
     * 递归获取 Notion 页面所有块内容，转为纯文本
     */
    private String fetchPageContent(String pageId) {
        StringBuilder sb = new StringBuilder();
        fetchBlockChildren(pageId, sb, 0);
        return sb.toString().trim();
    }

    private void fetchBlockChildren(String blockId, StringBuilder sb, int depth) {
        if (depth > 5) return; // 防止无限递归
        try {
            String cursor = null;
            do {
                String url = NOTION_API_BASE + "/blocks/" + blockId + "/children?page_size=100";
                if (cursor != null) {
                    url += "&start_cursor=" + cursor;
                }

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + notionToken)
                        .header("Notion-Version", NOTION_VERSION)
                        .GET()
                        .timeout(Duration.ofSeconds(15))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) break;

                JsonNode root = objectMapper.readTree(response.body());
                JsonNode results = root.path("results");

                for (JsonNode block : results) {
                    String blockType = block.path("type").asText();
                    String text = extractBlockText(block, blockType);
                    if (StringUtils.isNotBlank(text)) {
                        sb.append(text).append("\n");
                    }
                    // 递归处理子块
                    if (block.path("has_children").asBoolean()) {
                        String childId = block.path("id").asText();
                        fetchBlockChildren(childId, sb, depth + 1);
                    }
                }

                cursor = root.path("has_more").asBoolean() ? root.path("next_cursor").asText(null) : null;
            } while (cursor != null);

        } catch (Exception e) {
            // 忽略单个块的错误，继续处理
        }
    }

    /**
     * 从块节点中提取文本内容
     */
    private String extractBlockText(JsonNode block, String blockType) {
        JsonNode typeNode = block.path(blockType);
        if (typeNode.isMissingNode()) return "";

        // 大多数文本块有 rich_text 数组
        JsonNode richText = typeNode.path("rich_text");
        StringBuilder text = new StringBuilder();

        if (richText.isArray()) {
            for (JsonNode rt : richText) {
                text.append(rt.path("plain_text").asText(""));
            }
        }

        String content = text.toString().trim();
        if (StringUtils.isBlank(content)) return "";

        // 根据块类型添加 Markdown 前缀
        return switch (blockType) {
            case "heading_1" -> "# " + content;
            case "heading_2" -> "## " + content;
            case "heading_3" -> "### " + content;
            case "bulleted_list_item" -> "- " + content;
            case "numbered_list_item" -> "1. " + content;
            case "to_do" -> {
                boolean checked = typeNode.path("checked").asBoolean();
                yield (checked ? "- [x] " : "- [ ] ") + content;
            }
            case "quote" -> "> " + content;
            case "code" -> "```\n" + content + "\n```";
            case "callout" -> "💡 " + content;
            case "toggle" -> content;
            default -> content;
        };
    }

    /**
     * 调用 AI 根据 Notion 页面内容生成测试用例
     */
    private String callAiToGenerateCases(String pageTitle, String pageContent, String userId,
                                          String chatModelId, String organizationId) {
        // 如果前端未传 chatModelId，自动取用户可用的第一个模型
        String resolvedModelId = chatModelId;
        if (StringUtils.isBlank(resolvedModelId)) {
            List<OptionDTO> modelList = systemAIConfigService.getModelSourceNameList(userId);
            if (modelList == null || modelList.isEmpty()) {
                throw new MSException("未找到可用的 AI 模型，请在系统设置中配置 AI 模型");
            }
            resolvedModelId = modelList.get(0).getId();
        }

        AIChatRequest aiChatRequest = new AIChatRequest();
        aiChatRequest.setConversationId(UUID.randomUUID().toString());
        aiChatRequest.setPrompt(pageContent);
        aiChatRequest.setChatModelId(resolvedModelId);
        aiChatRequest.setOrganizationId(StringUtils.defaultIfBlank(organizationId, ""));

        AiModelSourceDTO module = aiChatBaseService.getModule(aiChatRequest, userId);

        String prompt = String.format("""
                你是一名专业的软件测试工程师。请根据以下需求文档内容，生成详细的功能测试用例，充分覆盖正常流程、边界条件和异常情况。

                需求文档标题：%s

                需求文档内容：
                %s

                **输出格式要求（严格遵守，不得有任何额外文字）：**
                - 每条用例以独立行 `featureCaseStart` 开头，以独立行 `featureCaseEnd` 结尾
                - 用例标题格式：`## 中文标题`（字符数不超过255）
                - 标题下一行输出 `caseExpand`
                - 禁止在用例容器外输出任何内容

                **输出示例（严格按此结构）：**
                featureCaseStart
                ## 用例标题
                caseExpand
                ### 前置条件
                前置条件内容
                ### 步骤描述
                | 用例步骤 | 预期结果 |
                | -------- | -------- |
                | 步骤1 | 预期结果1 |
                featureCaseEnd
                """, pageTitle, pageContent);

        AIChatOption option = AIChatOption.builder()
                .conversationId(aiChatRequest.getConversationId())
                .module(module)
                .prompt(prompt)
                .build();

        String result = aiChatBaseService.chat(option).content();
        if (StringUtils.isBlank(result)) {
            throw new MSException("AI 生成测试用例失败，请检查 AI 配置");
        }
        return result;
    }
}
