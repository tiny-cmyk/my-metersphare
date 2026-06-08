package io.metersphere.functional.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.metersphere.functional.dto.NotionCaseRow;
import io.metersphere.functional.dto.NotionChildBlock;
import io.metersphere.functional.domain.FunctionalCase;
import io.metersphere.functional.domain.FunctionalCaseBlob;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    // =================== Notion 页面层级遍历 ===================

    /**
     * 获取指定页面的所有直接子块（child_page 和 child_database 类型）
     * 用于从产品页面（web4.0）往下遍历找到所有数据库
     */
    public List<NotionChildBlock> getChildBlocks(String pageId) {
        List<NotionChildBlock> result = new ArrayList<>();
        String cursor = null;
        do {
            try {
                String url = NOTION_API_BASE + "/blocks/" + pageId + "/children?page_size=100";
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
                for (JsonNode block : root.path("results")) {
                    String type = block.path("type").asText("");
                    String id = block.path("id").asText("");
                    if (StringUtils.isBlank(id)) continue;

                    if ("child_page".equals(type)) {
                        String title = block.path("child_page").path("title").asText("");
                        result.add(new NotionChildBlock(id, title, "child_page"));
                    } else if ("child_database".equals(type)) {
                        String title = block.path("child_database").path("title").asText("");
                        result.add(new NotionChildBlock(id, title, "child_database"));
                    }
                }
                cursor = root.path("has_more").asBoolean() ? root.path("next_cursor").asText(null) : null;
            } catch (Exception e) {
                break;
            }
        } while (cursor != null);
        return result;
    }

    // =================== Notion Database 读写（双向同步核心） ===================

    /**
     * 查询 Notion 数据库中所有用例行（支持分页，自动遍历）
     *
     * @param databaseId Notion 数据库 ID（不带连字符的32位或带连字符的UUID格式）
     * @return 所有行解析后的 NotionCaseRow 列表（包含 archived=true 的行，调用方自行过滤）
     */
    public List<NotionCaseRow> queryDatabase(String databaseId) {
        List<NotionCaseRow> result = new ArrayList<>();
        String cursor = null;
        do {
            try {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("page_size", 100);
                if (cursor != null) {
                    body.put("start_cursor", cursor);
                }
                String bodyStr = objectMapper.writeValueAsString(body);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(NOTION_API_BASE + "/databases/" + databaseId + "/query"))
                        .header("Authorization", "Bearer " + notionToken)
                        .header("Notion-Version", NOTION_VERSION)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                        .timeout(Duration.ofSeconds(30))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    break;
                }

                JsonNode root = objectMapper.readTree(response.body());
                for (JsonNode page : root.path("results")) {
                    NotionCaseRow row = parsePageToRow(page);
                    if (row != null) {
                        result.add(row);
                    }
                }
                cursor = root.path("has_more").asBoolean() ? root.path("next_cursor").asText(null) : null;
            } catch (Exception e) {
                break;
            }
        } while (cursor != null);
        return result;
    }

    /**
     * 将 Notion 页面 JSON 解析为 NotionCaseRow
     */
    private NotionCaseRow parsePageToRow(JsonNode page) {
        try {
            NotionCaseRow row = new NotionCaseRow();
            row.setPageId(page.path("id").asText());
            row.setLastEditedTime(page.path("last_edited_time").asText());
            row.setArchived(page.path("archived").asBoolean(false));

            JsonNode props = page.path("properties");
            row.setName(extractTitle(props, "用例名称"));
            row.setPriority(extractSelect(props, "优先级"));
            row.setPrerequisite(extractRichText(props, "前置条件"));
            row.setSteps(extractRichText(props, "测试步骤"));
            row.setExpectedResult(extractRichText(props, "预期结果"));
            row.setDescription(extractRichText(props, "备注"));
            row.setModulePath(extractRichText(props, "模块路径"));
            row.setCreatorName(extractRichText(props, "创建人"));
            row.setTags(extractMultiSelect(props, "标签"));
            return row;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractTitle(JsonNode props, String key) {
        JsonNode arr = props.path(key).path("title");
        if (arr.isArray() && !arr.isEmpty()) {
            return arr.get(0).path("plain_text").asText("");
        }
        return "";
    }

    private String extractRichText(JsonNode props, String key) {
        JsonNode arr = props.path(key).path("rich_text");
        if (arr.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode rt : arr) {
                sb.append(rt.path("plain_text").asText(""));
            }
            return sb.toString();
        }
        return "";
    }

    private String extractSelect(JsonNode props, String key) {
        JsonNode prop = props.path(key);
        // 兼容 Notion 的 Select 和 Status 两种字段类型
        for (String typeKey : new String[]{"select", "status"}) {
            JsonNode node = prop.path(typeKey);
            if (!node.isMissingNode() && !node.isNull()) {
                return node.path("name").asText("");
            }
        }
        return "";
    }

    private List<String> extractMultiSelect(JsonNode props, String key) {
        List<String> result = new ArrayList<>();
        JsonNode arr = props.path(key).path("multi_select");
        if (arr.isArray()) {
            for (JsonNode item : arr) {
                result.add(item.path("name").asText(""));
            }
        }
        return result;
    }

    /**
     * 将 MeterSphere 用例变更推送回 Notion（更新 Notion 页面属性）
     *
     * @param notionPageId Notion 页面 ID
     * @param msCase       更新后的 FunctionalCase
     * @param blob         更新后的 FunctionalCaseBlob
     * @param priority     优先级（P0-P3，来自自定义字段）
     */
    public void updateNotionPage(String notionPageId, FunctionalCase msCase,
                                 FunctionalCaseBlob blob, String priority) {
        try {
            ObjectNode properties = objectMapper.createObjectNode();

            // 用例名称
            ArrayNode titleArr = objectMapper.createArrayNode();
            titleArr.addObject().putObject("text").put("content",
                    StringUtils.defaultString(msCase.getName()));
            properties.set("用例名称", objectMapper.createObjectNode().set("title", titleArr));

            // 优先级
            if (StringUtils.isNotBlank(priority)) {
                properties.set("优先级", objectMapper.createObjectNode()
                        .set("select", objectMapper.createObjectNode().put("name", priority)));
            }

            // 前置条件
            if (blob != null && blob.getPrerequisite() != null) {
                properties.set("前置条件", buildRichTextProp(
                        new String(blob.getPrerequisite(), StandardCharsets.UTF_8)));
            }

            // 测试步骤
            if (blob != null && blob.getSteps() != null) {
                properties.set("测试步骤", buildRichTextProp(
                        new String(blob.getSteps(), StandardCharsets.UTF_8)));
            }

            // 预期结果
            if (blob != null && blob.getExpectedResult() != null) {
                properties.set("预期结果", buildRichTextProp(
                        new String(blob.getExpectedResult(), StandardCharsets.UTF_8)));
            }

            // 备注
            if (blob != null && blob.getDescription() != null) {
                properties.set("备注", buildRichTextProp(
                        new String(blob.getDescription(), StandardCharsets.UTF_8)));
            }

            // 标签
            if (msCase.getTags() != null && !msCase.getTags().isEmpty()) {
                ArrayNode multiSelect = objectMapper.createArrayNode();
                msCase.getTags().forEach(tag -> multiSelect.addObject().put("name", tag));
                properties.set("标签", objectMapper.createObjectNode().set("multi_select", multiSelect));
            }

            ObjectNode patchBody = objectMapper.createObjectNode();
            patchBody.set("properties", properties);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(NOTION_API_BASE + "/pages/" + notionPageId))
                    .header("Authorization", "Bearer " + notionToken)
                    .header("Notion-Version", NOTION_VERSION)
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(patchBody)))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            // 推送失败不影响主流程，记录日志即可
        }
    }

    /**
     * 在 Notion 中将页面归档（软删除）
     */
    public void archiveNotionPage(String notionPageId) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("archived", true);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(NOTION_API_BASE + "/pages/" + notionPageId))
                    .header("Authorization", "Bearer " + notionToken)
                    .header("Notion-Version", NOTION_VERSION)
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            // 归档失败不影响主流程
        }
    }

    private ObjectNode buildRichTextProp(String content) {
        // Notion rich_text 单个元素最大 2000 字符，超出截断
        String safeContent = StringUtils.left(content, 2000);
        ArrayNode richTextArr = objectMapper.createArrayNode();
        richTextArr.addObject().putObject("text").put("content", safeContent);
        return objectMapper.createObjectNode().set("rich_text", richTextArr);
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
