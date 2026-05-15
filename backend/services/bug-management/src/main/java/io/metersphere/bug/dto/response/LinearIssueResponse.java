package io.metersphere.bug.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class LinearIssueResponse {

    @Schema(description = "Linear Issue ID")
    private String id;

    @Schema(description = "Issue 编号，如 ENG-123")
    private String identifier;

    @Schema(description = "Issue 标题")
    private String title;

    @Schema(description = "Issue 链接")
    private String url;
}
