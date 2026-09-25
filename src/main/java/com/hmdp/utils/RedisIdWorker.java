package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 定义初始时间戳
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    // 定义序列号向左移位位数
    private static final int COUNT_BITS = 32;

    public long nextId(String keyPrefix) {
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        Long nowSeconds = now.toEpochSecond(ZoneOffset.UTC);
        Long timestamp = nowSeconds - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));

        // 2.2自增长
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3.拼接时间戳和序列号并返回
        return timestamp << COUNT_BITS | count;

    }


}
