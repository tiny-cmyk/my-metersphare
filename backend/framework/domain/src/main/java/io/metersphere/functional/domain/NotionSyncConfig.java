package io.metersphere.functional.domain;

import lombok.Data;

import java.io.Serializable;

@Data
public class NotionSyncConfig implements Serializable {

    private String id;

    /** Notion 产品页面 ID（如 web4.0、app4.0、desktop4.0 的 Notion 页面 ID）*/
    private String notionPageId;

    private String projectId;

    private Boolean enabled;

    private Long createTime;

    private Long updateTime;

    private static final long serialVersionUID = 1L;
}
