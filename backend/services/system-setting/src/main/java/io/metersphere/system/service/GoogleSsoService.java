package io.metersphere.system.service;

import io.metersphere.sdk.constants.UserSource;
import io.metersphere.sdk.util.JSON;
import io.metersphere.sdk.util.LogUtils;
import io.metersphere.system.config.GoogleSsoProperties;
import io.metersphere.system.dto.user.UserCreateInfo;
import io.metersphere.system.dto.user.UserDTO;
import io.metersphere.system.dto.user.request.UserBatchCreateRequest;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 校验 ScriptPlatform 颁发的同源 sid cookie，并在本地按 email 做 JIT 用户置备。
 *
 * <p>调用 {@code GoogleSsoProperties#getMeEndpoint()}（默认 {@code /scriptPlatform/api/v1/me}），
 * 把请求里的 sid cookie 透传过去；返回 200 即认定身份有效，依据 email 在本地 user 表查找，
 * 若不存在则通过 {@link SimpleUserService#addUser} 用 source=GOOGLE_SSO 建立用户。</p>
 */
@Service
public class GoogleSsoService {

    @Resource
    private GoogleSsoProperties properties;
    @Resource
    private UserLoginService userLoginService;
    @Resource
    private SimpleUserService simpleUserService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public GoogleUserInfo validateSidCookie(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return null;
        }
        String sid = readCookie(request, properties.getSessionCookie());
        if (StringUtils.isBlank(sid)) {
            return null;
        }
        URI endpoint = resolveEndpoint(request);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMillis()))
                .header("Cookie", properties.getSessionCookie() + "=" + sid)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LogUtils.info("Google SSO /me returned non-200: {}", resp.statusCode());
                return null;
            }
            return parseMe(resp.body());
        } catch (Exception e) {
            LogUtils.warn("Google SSO /me call failed: " + e.getMessage(), e);
            return null;
        }
    }

    public UserDTO findOrCreateUser(GoogleUserInfo info) {
        if (info == null || StringUtils.isBlank(info.email())) {
            return null;
        }
        UserDTO existing = userLoginService.getUserDTONoXpack(info.email());
        if (existing != null) {
            return existing;
        }
        UserBatchCreateRequest createRequest = new UserBatchCreateRequest();
        createRequest.setUserRoleIdList(new ArrayList<>(Collections.singletonList(properties.getDefaultRoleId())));
        UserCreateInfo create = new UserCreateInfo();
        create.setEmail(info.email());
        create.setName(StringUtils.defaultIfBlank(info.name(), info.email()));
        List<UserCreateInfo> list = new ArrayList<>();
        list.add(create);
        createRequest.setUserInfoList(list);
        simpleUserService.addUser(createRequest, UserSource.GOOGLE_SSO.name(), properties.getJitOperator());
        return userLoginService.getUserDTONoXpack(info.email());
    }

    private URI resolveEndpoint(HttpServletRequest request) {
        String me = properties.getMeEndpoint();
        if (StringUtils.isNotBlank(properties.getUpstreamBaseUrl())) {
            String base = StringUtils.stripEnd(properties.getUpstreamBaseUrl(), "/");
            return URI.create(base + me);
        }
        // 同源部署：用当前请求自身的 scheme+host 拼相对路径
        String scheme = StringUtils.defaultIfBlank(request.getHeader("X-Forwarded-Proto"), request.getScheme());
        String host = StringUtils.defaultIfBlank(request.getHeader("X-Forwarded-Host"), request.getHeader("Host"));
        if (StringUtils.isBlank(host)) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        return URI.create(scheme + "://" + host + me);
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (StringUtils.equals(c.getName(), name)) {
                return c.getValue();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private GoogleUserInfo parseMe(String body) {
        if (StringUtils.isBlank(body)) {
            return null;
        }
        Map<String, Object> map = JSON.parseObject(body, Map.class);
        // ScriptPlatform 返回 {user_uid, email, display_name, avatar_url, is_platform_admin, ...}
        // 容错也兼容嵌套到 data/user 节点的情况。
        Map<String, Object> source = map;
        Object data = map.get("data");
        if (data instanceof Map) {
            source = (Map<String, Object>) data;
        }
        Object userNode = source.get("user");
        if (userNode instanceof Map) {
            source = (Map<String, Object>) userNode;
        }
        String email = asString(source.get("email"));
        if (StringUtils.isBlank(email)) {
            return null;
        }
        String name = StringUtils.firstNonBlank(
                asString(source.get("display_name")),
                asString(source.get("name")),
                email
        );
        String sub = StringUtils.firstNonBlank(
                asString(source.get("google_sub")),
                asString(source.get("user_uid")),
                asString(source.get("sub"))
        );
        return new GoogleUserInfo(email, name, sub);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record GoogleUserInfo(String email, String name, String sub) {
    }
}
