package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    public StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopById(Long id) {
        
        // 解决缓存穿透问题
        /*
        Shop shop = queryWithThrough(id);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        */
        
        // 利用互斥锁解决缓存穿透问题
        /*
        Shop shop = queryWithLock(id);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        */
        
        // 利用逻辑过期解决缓存穿透问题
        Shop shop = queryWithLogiclExpire(id);

        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        
        // 返回数据
        return Result.ok(shop);
        
    }

    // 利用逻辑过期解决缓存穿透问题方法
    public Shop queryWithLogiclExpire(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;

        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在缓存
        if (StrUtil.isBlank(shopJson)) {
            // 3.未命中缓存，查数据库
            Shop shop = getById(id);
            if (shop == null) {
                return null;
            }
            // 首次访问，存入redis并设置逻辑过期时间
            saveShop2Redis(id, 20L);
            return shop;
        }

        // 4.命中缓存，将json反序列化为RedisData对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 5.判断逻辑过期时间
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 6.未过期，直接返回商铺信息
            return shop;
        }

        // 7.已过期，尝试获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);

        if (isLock) {
            // 8.获取锁成功，再次检查redis缓存是否已被其他线程重建（double-check）
            String doubleCheckJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(doubleCheckJson)) {
                RedisData doubleCheckData = JSONUtil.toBean(doubleCheckJson, RedisData.class);
                if (doubleCheckData.getExpireTime().isAfter(LocalDateTime.now())) {
                    // 其他线程已重建缓存，直接返回新数据
                    releaseLock(lockKey);
                    return JSONUtil.toBean((JSONObject) doubleCheckData.getData(), Shop.class);
                }
            }

            // 9.确实过期，开启独立线程缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    releaseLock(lockKey);
                }
            });
        }

        // 10.获取锁失败或已提交重建，直接返回过期的商铺信息
        return shop;
    }

    // 利用互斥锁解决缓存穿透问题方法
    public Shop queryWithLock(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;

        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在缓存
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.如果存在缓存，直接返回缓存数据
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        // 4.判断是否存在空值缓存（缓存穿透防御：之前查过且不存在，缓存了""）
        if (shopJson != null) {
            // shopJson为""，说明已缓存了空值防止穿透
            return null;
        }

        // 5. 尝试获取互斥锁
        String lockKey = "lock:shop" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);

            // 6. 如果获取失败，休眠一段时间后重试
            if (!isLock) {
                Thread.sleep(10);
                return queryWithLock(id);
            }

            // 7. 如果获取成功，再次检测redis缓存是否存在（获取锁期间可能其他线程已写入）
            String lockShopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(lockShopJson)) {
                // 8.如果存在缓存，直接返回缓存数据
                shop = JSONUtil.toBean(lockShopJson, Shop.class);
                return shop;
            }

            // 8.若不存在，继续执行后续逻辑，根据id查询数据库数据    
            shop = getById(id);

            // 9.数据库若不存在，返回null
            if (shop == null) {
                // 存入空值缓存
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回失败结果
                return null;
            }

            // 10.数据库若存在，将数据缓存到redis中
            stringRedisTemplate.opsForValue().
                    set(key,
                            JSONUtil.toJsonStr(shop),
                            RedisConstants.CACHE_SHOP_TTL,
                            TimeUnit.MINUTES);
        }catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally { 
            
            // 11. 释放互斥锁
            releaseLock(lockKey);
        }
        // 12. 返回数据
        return shop;
    }
        
    // 解决缓存穿透问题方法
    public Shop queryWithThrough(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;

        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在缓存
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.如果存在缓存，直接返回缓存数据
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        // 4.判断是否存在空值缓存（缓存穿透防御）
        if (shopJson != null) {
            return null;
        }

        // 5.如果不存在缓存，根据id查询数据库数据
        Shop shop = getById(id);

        // 6.数据库若不存在，返回null
        if (shop == null) {
            // 存入空值缓存
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回失败结果
            return null;
        }

        // 7.数据库若存在，将数据缓存到redis中，返回数据
        stringRedisTemplate.opsForValue().
                set(key,
                        JSONUtil.toJsonStr(shop),
                        RedisConstants.CACHE_SHOP_TTL,
                        TimeUnit.MINUTES);
        return shop;
    }

    // 线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 逻辑过期
    private void saveShop2Redis(Long id, Long expireSeconds) {
        // 1.查询店铺数据
        Shop shop = getById(id);

        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        // 3.写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
    
    // 创建互斥锁方法
    private boolean tryLock(String key) {
        Boolean flag = BooleanUtil.isTrue(stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS));
        return BooleanUtil.isTrue(flag);
    }
    
    // 释放互斥锁方法
    private void releaseLock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 更新商铺信息
     * @param shop 商铺数据
     * @return 无
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long shopId = shop.getId();

        // 1.更新数据库
        updateById(shop);

        // 2.删除redis缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shopId);
        return Result.ok();
    }


}