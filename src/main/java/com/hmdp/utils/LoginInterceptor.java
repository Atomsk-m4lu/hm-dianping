package com.hmdp.utils;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoginInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        log.info("=== LoginInterceptor === URI={}, UserHolder.getUser()={}",
                request.getRequestURI(), UserHolder.getUser());

        // 1.判断是否要拦截(ThreadLocal中是否有用户)
        if (UserHolder.getUser() == null) {
            log.info("→ 用户未登录，拦截！返回401");
            //没有，需要拦截，设置状态码
            response.setStatus(401);
            return false;
        }

        log.info("→ 用户已登录(id={})，放行", UserHolder.getUser().getId());
        // 2.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) throws Exception {
        log.info("=== LoginInterceptor.afterCompletion === 清理UserHolder");
        // 请求结束，清除ThreadLocal，避免线程复用导致数据错乱
        UserHolder.removeUser();
    }
}