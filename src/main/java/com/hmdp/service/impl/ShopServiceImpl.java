package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数

        // 3.查询redis，按照距离排序、分页。结果：shopId,distance

        // 4.解析出id

        // 5.根据id查询商铺信息


        // 6.返回结果
        return Result.ok(shops);
    }
}