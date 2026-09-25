package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据顺序查询商铺类型列表
     * @return 商铺类型列表
     */
    @Override
    public Result orderByType() {
        String key = RedisConstants.SHOP_TYPE_KEY;

        // 1.从redis中查询商铺类型列表
        List<String> typeListJson = stringRedisTemplate.opsForList().range(key, 0, -1);

        // 2.如果redis中有数据,则将每个json字符串转为ShopType对象并返回
        if (typeListJson != null && !typeListJson.isEmpty()) {
            List<ShopType> list = typeListJson.stream()
                    .map(json -> JSONUtil.toBean(json, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(list);
        }

        // 3.如果redis中没有数据,则从数据库中按sort字段升序查询
        List<ShopType> list = baseMapper.selectList(
                new LambdaQueryWrapper<ShopType>().orderByAsc(ShopType::getSort)
        );

        // 4.若数据库中也没有数据,则返回空列表
        if (list.isEmpty()) {
            return Result.fail("商铺类型列表为空");
        }

        // 5.若数据库中也有数据,则将数据缓存到redis中,并返回数据
        String[] jsonArr = list.stream().map(JSONUtil::toJsonStr).toArray(String[]::new);
        stringRedisTemplate.opsForList().rightPushAll(key, jsonArr);
        return Result.ok(list);
    }
}