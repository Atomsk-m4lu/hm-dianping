-- 1.参数列表
-- 1.1优惠券id
local voucherId = ARGV[1]

-- 1.2用户id
local userId = ARGV[2]

-- 2.数据key
-- 2.1优惠券库存key
local stockKey = "seckill:stock:" .. voucherId

-- 2.2订单key
local orderKey = "seckill:order:" .. voucherId

-- 3.脚本业务
-- 3.1检查库存是否充足 get stockKey
if(redis.call("get", stockKey) <= 0) then

    -- 3.2库存不足，return 1
    return 1
end

-- 3.3检查用户是否已下单 sismember orderKey userId
if(redis.call("sismember", orderKey, userId) == 1) then

    -- 3.4用户已下单，return 2
    return 2
end

-- 3.5库存充足，用户未下单，扣减库存 incrby stockKey -1
redis.call("incrby", stockKey, -1)

-- 3.6用户下单成功，将用户id添加到订单集合中 sadd orderKey userId
redis.call("sadd", orderKey, userId)

-- 3.7用户下单成功，return 0
return 0
