package io.metersphere.functional.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class NotionSyncRequest {

    @Schema(description = "Notion 数据库 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Notion 数据库 ID 不能为空")
    private String databaseId;

    @Schema(description = "MeterSphere 项目 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "项目 ID 不能为空")
    private String projectId;

    @Schema(description = "默认模块 ID（不填则使用根节点）")
    private String moduleId;

    @Schema(description = "模板 ID（不填则自动使用项目默认模板）")
    private String templateId;
}
