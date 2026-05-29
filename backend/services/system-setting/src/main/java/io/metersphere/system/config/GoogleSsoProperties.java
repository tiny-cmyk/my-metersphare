package io.metersphere.system.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = GoogleSsoProperties.PREFIX)
public class GoogleSsoProperties {
    public static final String PREFIX = "metersphere.sso.google";

    /**
     * 是否启用 Google SSO 引导。关闭时 /sso/google/bootstrap 直接返回 401。
     */
    private boolean enabled = true;

    /**
     * 上游身份源（ScriptPlatform）的 /me 端点，同源部署下默认走相对路径。
     */
    private String meEndpoint = "/scriptPlatform/api/v1/me";

    /**
     * 同源访问时使用的 base URL。留空则按需用请求自身的 scheme+host 拼接 meEndpoint。
     */
    private String upstreamBaseUrl = "";

    /**
     * ScriptPlatform 颁发的会话 cookie 名，默认 sid。
     */
    private String sessionCookie = "sid";

    /**
     * /me 调用超时（毫秒）。
     */
    private int requestTimeoutMillis = 5000;

    /**
     * JIT 建用户时分配的默认全局角色 ID 列表。
     */
    private String defaultRoleId = "member";

    /**
     * JIT 建用户时记录的 operator，用于审计列。
     */
    private String jitOperator = "google_sso";
}
