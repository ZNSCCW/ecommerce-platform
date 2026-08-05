-- Redis Lua 脚本：原子扣减秒杀库存
-- KEYS[1] = 库存 key (seckill:stock:{activityId}:{skuId})
-- KEYS[2] = 用户限购 key (seckill:limit:{activityId}:{skuId}:{userId})
-- ARGV[1] = 扣减数量
-- ARGV[2] = 每人限购数量
-- ARGV[3] = 限购过期时间（秒，活动持续时间）
-- 返回值: 0=库存不足 1=成功 -1=超过限购

local stock = redis.call('get', KEYS[1])
if not stock or tonumber(stock) < tonumber(ARGV[1]) then
    return 0  -- 库存不足
end

local userBuy = redis.call('get', KEYS[2])
if userBuy and tonumber(userBuy) >= tonumber(ARGV[2]) then
    return -1  -- 超过限购
end

-- 扣减库存
redis.call('decrby', KEYS[1], tonumber(ARGV[1]))

-- 累计用户购买数量（限购计数）
if userBuy then
    redis.call('incrby', KEYS[2], tonumber(ARGV[1]))
else
    redis.call('setex', KEYS[2], tonumber(ARGV[3]), tonumber(ARGV[1]))
end

return 1  -- 扣减成功
