package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 线程池（用于异步缓存重建）
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 写入缓存（普通过期时间）
     */
    public void set(String key, Object value, long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 写入缓存（带逻辑过期字段）
     */
    public void setWithLogicalExpire(String key, Object value, long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 通用查询：逻辑过期解决缓存击穿
     *
     * @param keyPrefix      缓存key前缀（如 "cache:shop:"）
     * @param lockKeyPrefix  锁key前缀（如 "lock:shop:"）
     * @param id             业务id
     * @param type           返回值类型
     * @param dbFallback     数据库查询回调
     * @param time           逻辑过期时间数值
     * @param unit           逻辑过期时间单位
     * @param <R>            返回值类型
     * @param <ID>           id类型
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix,
            String lockKeyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit) {

        String key = keyPrefix + id;

        // 1. 从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断缓存是否命中
        if (StrUtil.isBlank(json)) {
            // 3. 未命中，查数据库
            R data = dbFallback.apply(id);
            if (data == null) {
                return null;
            }
            // 首次访问，写入redis（带逻辑过期时间）
            saveWithLogicalExpire(key, data, time, unit);
            return data;
        }

        // 4. 命中，反序列化 RedisData
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R data = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 5. 判断是否逻辑过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 6. 未过期，直接返回
            return data;
        }

        // 7. 已过期，尝试获取互斥锁
        String lockKey = lockKeyPrefix + id;
        boolean isLock = tryLock(lockKey);

        if (isLock) {
            // 8. 获取锁成功，double-check
            String doubleCheckJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(doubleCheckJson)) {
                RedisData doubleCheckData = JSONUtil.toBean(doubleCheckJson, RedisData.class);
                if (doubleCheckData.getExpireTime().isAfter(LocalDateTime.now())) {
                    // 其他线程已重建，返回新数据
                    releaseLock(lockKey);
                    return JSONUtil.toBean((JSONObject) doubleCheckData.getData(), type);
                }
            }

            // 9. 确实过期，异步重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R freshData = dbFallback.apply(id);
                    saveWithLogicalExpire(key, freshData, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    releaseLock(lockKey);
                }
            });
        }

        // 10. 返回过期数据（脏数据兜底）
        return data;
    }

    /**
     * 通用查询：空值缓存解决缓存穿透
     *
     * @param keyPrefix   缓存key前缀
     * @param id          业务id
     * @param type        返回值类型
     * @param dbFallback  数据库查询回调
     * @param time        过期时间数值
     * @param unit        过期时间单位
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit) {

        String key = keyPrefix + id;

        // 1. 查缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 命中真实数据，直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        // 3. 命中空值（缓存穿透防御），返回null
        if (json != null) {
            return null;
        }

        // 4. 未命中，查数据库
        R data = dbFallback.apply(id);

        // 5. 数据库不存在，缓存空值防穿透
        if (data == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 6. 数据库存在，写入缓存并返回
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(data), time, unit);
        return data;
    }

    /**
     * 通用查询：互斥锁解决缓存击穿
     *
     * @param keyPrefix      缓存key前缀
     * @param lockKeyPrefix  锁key前缀
     * @param id             业务id
     * @param type           返回值类型
     * @param dbFallback     数据库查询回调
     * @param time           过期时间数值
     * @param unit           过期时间单位
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix,
            String lockKeyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit) {

        String key = keyPrefix + id;

        // 1. 查缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 命中真实数据，直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        // 3. 命中空值（穿透防御），返回null
        if (json != null) {
            return null;
        }

        // 4. 未命中，尝试获取互斥锁
        String lockKey = lockKeyPrefix + id;
        R data = null;
        try {
            boolean isLock = tryLock(lockKey);

            // 5. 获取锁失败，休眠后递归重试
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, lockKeyPrefix, id, type, dbFallback, time, unit);
            }

            // 6. 获取锁成功，double-check缓存
            String doubleCheckJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(doubleCheckJson)) {
                return JSONUtil.toBean(doubleCheckJson, type);
            }

            // 7. 查数据库
            data = dbFallback.apply(id);

            // 8. 数据库不存在，缓存空值防穿透
            if (data == null) {
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 9. 数据库存在，写入缓存
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(data), time, unit);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 10. 释放互斥锁
            releaseLock(lockKey);
        }

        return data;
    }

    /**
     * 写入缓存（带逻辑过期字段，内部用）
     */
    private <R> void saveWithLogicalExpire(String key, R data, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(data);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 获取互斥锁（基于 Redis setnx）
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁
     */
    private void releaseLock(String key) {
        stringRedisTemplate.delete(key);
    }
}