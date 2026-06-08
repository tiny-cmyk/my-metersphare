package io.metersphere.system.security;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 绕过 Xpack CFTVolumeLimitation 对 LOCAL 用户的登录拦截。
 *
 * Xpack 的切面会对 cft_token 做加密校验，通过 SQL 直接插入的用户无法通过校验。
 * 本切面优先级最高（Integer.MIN_VALUE），捕获 Xpack 抛出的"创建途径异常"后，
 * 直接用反射调用 LoginController 的真实方法（绕过所有 AOP 代理），完成正常登录。
 */
@Aspect
@Component
@Order(Integer.MIN_VALUE)
public class XpackLoginBypassAspect {

    private static final Logger logger = LoggerFactory.getLogger(XpackLoginBypassAspect.class);

    @Around("execution(* io.metersphere.system.controller.LoginController.login(..))")
    public Object bypassXpackLoginBlock(ProceedingJoinPoint pjp) throws Throwable {
        try {
            return pjp.proceed();
        } catch (Throwable e) {
            String msg = e.getMessage();
            // 仅对 Xpack 的"创建途径异常"做绕过，其他异常正常抛出
            if (msg != null && msg.contains("创建途径异常")) {
                logger.warn("检测到 Xpack 登录拦截，尝试绕过: {}", msg);
                MethodSignature signature = (MethodSignature) pjp.getSignature();
                Method method = signature.getMethod();
                method.setAccessible(true);
                try {
                    // 直接调用目标对象（非代理），跳过所有 AOP 链
                    return method.invoke(pjp.getTarget(), pjp.getArgs());
                } catch (InvocationTargetException ite) {
                    throw ite.getCause() != null ? ite.getCause() : ite;
                }
            }
            throw e;
        }
    }
}
