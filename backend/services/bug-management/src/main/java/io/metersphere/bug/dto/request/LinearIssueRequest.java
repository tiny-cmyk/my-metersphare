package io.metersphere.bug.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LinearIssueRequest {

    @Schema(description = "缺陷ID")
    @NotBlank
    private String bugId;

    @Schema(description = "缺陷编号")
    private Long num;

    @Schema(description = "缺陷标题")
    @NotBlank
    private String title;

    @Schema(description = "缺陷描述(HTML)")
    private String description;

    @Schema(description = "优先级")
    private String priority;

    @Schema(description = "平台链接")
    private String link;
}
