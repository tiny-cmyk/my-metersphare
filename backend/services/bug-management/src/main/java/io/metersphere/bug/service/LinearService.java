package io.metersphere.bug.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.metersphere.bug.dto.request.LinearIssueRequest;
import io.metersphere.bug.dto.response.LinearIssueResponse;
import io.metersphere.sdk.exception.MSException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
public class LinearService {

    private static final String LINEAR_API_URL = "https://api.linear.app/graphql";

    @Value("${integration.linear.api-token:}")
    private String linearApiToken;

    @Value("${integration.linear.team-id:}")
    private String linearTeamId;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 将 MeterSphere 缺陷提交到 Linear
     */
    public LinearIssueResponse createIssue(LinearIssueRequest request) {
        try {
            // 优先级映射: MeterSphere -> Linear (0=No priority, 1=Urgent, 2=High, 3=Medium, 4=Low)
            int linearPriority = mapPriority(request.getPriority());

            String mutation = """
                    mutation IssueCreate($input: IssueCreateInput!) {
                      issueCreate(input: $input) {
                        success
                        issue {
                          id
                          identifier
                          title
                          url
                        }
                      }
                    }
                    """;

            Map<String, Object> input = new HashMap<>();
            input.put("teamId", linearTeamId);
            input.put("title", request.getTitle());
            input.put("description", buildDescription(request));
            input.put("priority", linearPriority);

            Map<String, Object> variables = new HashMap<>();
            variables.put("input", input);

            Map<String, Object> body = new HashMap<>();
            body.put("query", mutation);
            body.put("variables", variables);

            String requestBody = objectMapper.writeValueAsString(body);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(LINEAR_API_URL))
                    .header("Authorization", linearApiToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new MSException("Linear API 请求失败，状态码: " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode issueCreate = root.path("data").path("issueCreate");

            if (!issueCreate.path("success").asBoolean()) {
                throw new MSException("Linear 创建 Issue 失败");
            }

            JsonNode issue = issueCreate.path("issue");
            LinearIssueResponse result = new LinearIssueResponse();
            result.setId(issue.path("id").asText());
            result.setIdentifier(issue.path("identifier").asText());
            result.setTitle(issue.path("title").asText());
            result.setUrl(issue.path("url").asText());
            return result;

        } catch (MSException e) {
            throw e;
        } catch (Exception e) {
            throw new MSException("提交 Linear 失败: " + e.getMessage());
        }
    }

    private String buildDescription(LinearIssueRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 来源\n");
        sb.append("MeterSphere 缺陷管理 #").append(request.getNum()).append("\n\n");
        if (request.getDescription() != null && !request.getDescription().isBlank()) {
            // 去掉 HTML 标签，保留纯文本
            String plainText = request.getDescription().replaceAll("<[^>]+>", "").trim();
            sb.append("## 缺陷描述\n").append(plainText).append("\n\n");
        }
        if (request.getLink() != null && !request.getLink().isBlank()) {
            sb.append("## 平台链接\n").append(request.getLink()).append("\n");
        }
        return sb.toString();
    }

    private int mapPriority(String priority) {
        if (priority == null) return 0;
        return switch (priority.toLowerCase()) {
            case "urgent", "critical", "紧急" -> 1;
            case "high", "高" -> 2;
            case "medium", "中" -> 3;
            case "low", "低" -> 4;
            default -> 0;
        };
    }
}
