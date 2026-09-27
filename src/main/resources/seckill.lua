-- 1.参数列表
-- 1.1优惠券id
local voucherId = ARGV[1]

-- 1.2用户id
local userId = ARGV[2]

-- 1.3订单id
local orderId = ARGV[3]

-- 2.数据key
-- 2.1优惠券库存key
local stockKey = "seckill:stock:" .. voucherId

-- 2.2订单key
local orderKey = "seckill:order:" .. voucherId

-- 3.脚本业务
-- 3.1检查库存是否充足 get stockKey
-- 注意：redis.call("get") 返回的是 string，必须用 tonumber() 转成数字才能和 0 比较
-- 还要用 or "0" 防御 key 不存在时 GET 返回 nil 的情况（nil 传给 tonumber 也会报错）
if(tonumber(redis.call("get", stockKey) or "0") <= 0) then

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

-- 3.7发送消息到队列 XADD stream.orders * k1 v1 k2 v2 ...
redis.call("xadd", "stream.orders", "*", "userId", userId, "voucherId", voucherId, "id", orderId)

-- 3.8用户下单成功，return 0
return 0