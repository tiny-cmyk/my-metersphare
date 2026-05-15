package io.metersphere.functional.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class NotionImportRequest {

    @Schema(description = "Notion 页面 URL", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Notion 页面 URL 不能为空")
    private String notionUrl;

    @Schema(description = "项目ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "项目ID不能为空")
    private String projectId;

    @Schema(description = "模块ID")
    private String moduleId;

    @Schema(description = "模板ID")
    private String templateId;

    @Schema(description = "AI模型ID")
    private String chatModelId;

    @Schema(description = "组织ID")
    private String organizationId;
}
