
-- 比较线程标示与锁中的标示是否一致
if(redis.call("get", KEYS[1]) == ARGV[1]) then
    -- 解锁
    redis.call("del", KEYS[1])
end
return 0
