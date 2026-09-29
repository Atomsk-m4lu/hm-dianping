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
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 应用启动时，将商铺坐标数据加载到 Redis GEO
     */
    @PostConstruct
    public void initShopGeoData() {
        List<Shop> shopList = list();
        if (shopList == null || shopList.isEmpty()) {
            return;
        }
        // 按 typeId 分组
        Map<Long, List<Shop>> map = shopList.stream()
                .collect(Collectors.groupingBy(Shop::getTypeId));
        // 写入 Redis GEO
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            String key = RedisConstants.SHOP_GEO_KEY + typeId;
            // 先删除旧数据，防止重复
            stringRedisTemplate.delete(key);
            List<Shop> shops = entry.getValue();
            for (Shop shop : shops) {
                stringRedisTemplate.opsForGeo().add(
                        key,
                        new Point(shop.getX(), shop.getY()),
                        shop.getId().toString()
                );
            }
        }
    }

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
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis，按照距离排序、分页。结果：shopId,distance
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        Circle circle = new Circle(new Point(x, y), new Distance(5000));
        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                stringRedisTemplate.opsForGeo().radius(
                        key, circle,
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .limit(end)
                );
        if (results == null || results.getContent().isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 4.解析出id
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            RedisGeoCommands.GeoLocation<String> location = result.getContent();
            ids.add(Long.valueOf(location.getName()));
            distanceMap.put(location.getName(), result.getDistance());
        });

        // 5.根据id查询商铺信息
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids)
                .last("ORDER BY FIELD(id, " + idStr + ")").list();
        shops.forEach(shop -> shop.setDistance(distanceMap.get(shop.getId().toString()).getValue()));

        // 6.去重（防止Redis GEO中有重复member）
        List<Shop> distinctShops = shops.stream()
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(Shop::getId, s -> s, (existing, replacement) -> existing, LinkedHashMap::new),
                        m -> new ArrayList<>(m.values())));

        // 7.返回结果
        return Result.ok(distinctShops);
    }
}