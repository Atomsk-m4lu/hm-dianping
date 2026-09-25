package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

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
        if(StrUtil.isBlank(token)) {

            // 2.如果token为空，放行（交给LoginInterceptor处理）
            return true;
        }

        // 3.获取用户信息从redis中获取
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
                .entries(key);

        // 4.判断用户是否存在
        if (userMap.isEmpty()) {

            // 5.如果用户不存在，放行（交给LoginInterceptor处理）
            return true;
        }
        
        // 6.将查询到的hash转换为userDTO
        UserDTO userDTO = new UserDTO();
        BeanUtil.fillBeanWithMap(userMap, userDTO, false);

        // 7.将userDTO保存到ThreadLocal中
        UserHolder.saveUser(userDTO);

        // 8.刷新token过期时间
        stringRedisTemplate.expire(key,
                RedisConstants.LOGIN_USER_TTL,
                TimeUnit.MINUTES);
        
        // 9.放行
        return true;
    }
}