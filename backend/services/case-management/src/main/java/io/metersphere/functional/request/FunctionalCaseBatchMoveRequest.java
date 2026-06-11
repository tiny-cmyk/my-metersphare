package io.metersphere.functional.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @author wx
 */
@Data
public class FunctionalCaseBatchMoveRequest extends FunctionalCaseBatchRequest {

    @Schema(description = "模块ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "{functional_case.module_id.not_blank}")
    private String moduleId;

    @Schema(description = "是否保留源模块层级结构（复制时有效）")
    private Boolean preserveModuleStructure = false;

    @Schema(description = "源根模块ID，用于计算相对路径（preserveModuleStructure=true 时必填）")
    private String sourceModuleId;
}
