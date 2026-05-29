package io.metersphere.system.controller;

import io.metersphere.project.domain.Project;
import io.metersphere.project.mapper.ProjectMapper;
import io.metersphere.sdk.constants.HttpMethodConstants;
import io.metersphere.sdk.constants.UserSource;
import io.metersphere.sdk.exception.MSException;
import io.metersphere.system.controller.handler.ResultHolder;
import io.metersphere.system.controller.handler.result.MsHttpResultCode;
import io.metersphere.system.dto.sdk.SessionUser;
import io.metersphere.system.dto.user.UserDTO;
import io.metersphere.system.log.constants.OperationLogType;
import io.metersphere.system.service.GoogleSsoService;
import io.metersphere.system.service.UserLoginService;
import io.metersphere.system.utils.SessionUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.session.Session;
import org.apache.shiro.subject.Subject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接收 ScriptPlatform 颁发的同源 sid cookie，建立 Shiro 会话。
 *
 * <p>{@code POST /sso/google/bootstrap}：校验 sid cookie，找不到或失效返回 401；
 * 命中则按 email JIT 置备本地用户，再以 source=GOOGLE_SSO 走 LocalRealm 的免密分支建立会话，
 * 返回结构与 {@code /login} 对齐（含 sessionId、csrfToken），前端直接写入 localStorage 即可。</p>
 */
@RestController
@RequestMapping("/sso/google")
@Tag(name = "Google SSO 引导")
public class GoogleSsoController {

    @Resource
    private GoogleSsoService googleSsoService;
    @Resource
    private UserLoginService userLoginService;
    @Resource
    private ProjectMapper projectMapper;

    @PostMapping("/bootstrap")
    @Operation(summary = "基于 ScriptPlatform sid cookie 建立本地会话")
    public ResultHolder bootstrap(HttpServletRequest request, HttpServletResponse response) {
        GoogleSsoService.GoogleUserInfo info = googleSsoService.validateSidCookie(request);
        if (info == null) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return ResultHolder.error(MsHttpResultCode.UNAUTHORIZED.getCode(), "google_sso_session_invalid");
        }
        UserDTO userDTO = googleSsoService.findOrCreateUser(info);
        if (userDTO == null) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return ResultHolder.error(MsHttpResultCode.UNAUTHORIZED.getCode(), "google_sso_user_not_provisioned");
        }
        Subject subject = SecurityUtils.getSubject();
        SessionUser current = SessionUtils.getUser();
        if (current != null && !StringUtils.equals(current.getId(), userDTO.getId())) {
            // 当前 Shiro 会话归属另一个账号，先注销避免 LoginController 风格的冲突。
            subject.logout();
            subject = SecurityUtils.getSubject();
        }
        Session session = subject.getSession();
        session.setAttribute("authenticate", UserSource.GOOGLE_SSO.name());
        try {
            subject.login(new UsernamePasswordToken(userDTO.getId(), ""));
        } catch (AuthenticationException e) {
            throw new MSException("Google SSO login failed: " + e.getMessage());
        }
        SessionUser sessionUser = SessionUtils.getUser();
        if (sessionUser == null) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return ResultHolder.error(MsHttpResultCode.UNAUTHORIZED.getCode(), "google_sso_session_build_failed");
        }
        userLoginService.autoSwitch(sessionUser);
        SessionUtils.putUser(sessionUser);
        // 项目状态修正，与 LoginController#isLogin 行为一致
        Project lastProject = projectMapper.selectByPrimaryKey(sessionUser.getLastProjectId());
        if (StringUtils.isBlank(sessionUser.getLastProjectId()) || lastProject == null || !lastProject.getEnable()) {
            sessionUser.setLastProjectId("no_such_project");
        }
        userLoginService.saveLog(sessionUser.getId(), HttpMethodConstants.POST.name(),
                "/sso/google/bootstrap", "Google SSO 登录成功", OperationLogType.LOGIN.name());
        return ResultHolder.success(sessionUser);
    }

    @GetMapping("/logout")
    @Operation(summary = "退出 Google SSO 引导出的本地会话")
    public ResultHolder logout() {
        SessionUser user = SessionUtils.getUser();
        if (user == null) {
            return ResultHolder.success("logout success");
        }
        userLoginService.saveLog(user.getId(), HttpMethodConstants.GET.name(),
                "/sso/google/logout", "Google SSO 登出成功", OperationLogType.LOGOUT.name());
        SecurityUtils.getSubject().logout();
        return ResultHolder.success("logout success");
    }
}
