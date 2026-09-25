package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopById(Long id) {

        // 1. 空值缓存 —— 解决缓存穿透
        // Shop shop = cacheClient.queryWithPassThrough(
        //         RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
        //         this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES
        // );

        // 2. 互斥锁 —— 解决缓存击穿
        // Shop shop = cacheClient.queryWithMutex(
        //         RedisConstants.CACHE_SHOP_KEY, RedisConstants.LOCK_SHOP_KEY,
        //         id, Shop.class, this::getById,
        //         RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES
        // );

        // 3. 逻辑过期 —— 解决缓存击穿（当前使用）
        Shop shop = cacheClient.queryWithLogicalExpire(
                RedisConstants.CACHE_SHOP_KEY, RedisConstants.LOCK_SHOP_KEY,
                id, Shop.class, this::getById,
                20L, TimeUnit.MINUTES
        );

        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long shopId = shop.getId();
        updateById(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shopId);
        return Result.ok();
    }
}