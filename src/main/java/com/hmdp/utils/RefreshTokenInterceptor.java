package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenInterceptor.class);

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        // 1.获取请求头中的token
        String token = request.getHeader("authorization");
        String key = RedisConstants.LOGIN_USER_KEY + token;
        log.info("=== RefreshTokenInterceptor === URI={}, token=「{}」, key=「{}」",
                request.getRequestURI(), token, key);

        if(StrUtil.isBlank(token)) {
            log.info("→ token为空，放行交给LoginInterceptor");
            // 2.如果token为空，放行（交给LoginInterceptor处理）
            return true;
        }

        // 3.获取用户信息从redis中获取
        log.info("→ 开始查询Redis: HGETALL {}", key);
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
                .entries(key);
        log.info("→ Redis查询结果: size={}, isEmpty={}", userMap.size(), userMap.isEmpty());

        // 4.判断用户是否存在
        if (userMap.isEmpty()) {
            log.info("→ Redis中无该token，不放UserHolder，放行交给LoginInterceptor");
            // 5.如果用户不存在，放行（交给LoginInterceptor处理）
            return true;
        }
        
        // 6.将查询到的hash转换为userDTO
        UserDTO userDTO = new UserDTO();
        BeanUtil.fillBeanWithMap(userMap, userDTO, false);

        // 7.将userDTO保存到ThreadLocal中
        UserHolder.saveUser(userDTO);
        log.info("→ Redis查到用户(id={})，已存入UserHolder！", userDTO.getId());

        // 8.刷新token过期时间
        stringRedisTemplate.expire(key,
                RedisConstants.LOGIN_USER_TTL,
                TimeUnit.SECONDS);
        
        // 9.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) throws Exception {
        log.info("=== RefreshTokenInterceptor.afterCompletion === 清理UserHolder");
        // 请求结束，清除ThreadLocal，避免线程复用导致数据错乱
        UserHolder.removeUser();
    }
}