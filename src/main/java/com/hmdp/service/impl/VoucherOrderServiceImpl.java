package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类 —— 秒杀下单（异步削峰方案，基于 Redis Stream）
 * </p>
 *
 * 设计思路：
 * 1. 生产者（seckillVoucher）在 Lua 脚本内原子完成校验 + 扣库存 + XADD 写 Stream
 * 2. 消费者（init 启动的后台线程）用 XREADGROUP 从 Stream 拉取消息，串行落库
 * 3. 为啥用 Redis Stream 而不是 BlockingQueue？
 *    - Stream 持久化在 Redis 中，JVM 重启不丢消息
 *    - 天然支持消费者组，未来可扩展到多实例并行消费
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * 秒杀 Lua 脚本，在类加载时初始化一次即可。
     * 脚本作用：在 Redis 中原子地完成「检查库存 → 检查一人一单 → 扣减库存 → 记录已购 → XADD 写 Stream」
     * 返回值：0=成功，1=库存不足，2=用户已下单
     * 参数：voucherId, userId, orderId（orderId 由 Java 端雪花算法生成后传入）
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * Stream 消费者线程池 —— 单线程串行消费，天然避免并发写库冲突
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private volatile boolean running = true;

    @PreDestroy
    public void destroy() {
        running = false;
        SECKILL_ORDER_EXECUTOR.shutdownNow();
    }

    /**
     * @PostConstruct: Spring 注入完成后自动执行。
     * 1. 创建消费者组（若已存在则忽略）
     * 2. 启动后台消费线程，持续从 Stream 中拉取订单消息
     */
    @PostConstruct
    public void init() {
        // 创建消费者组 "g1"，从 Stream 头部开始消费
        // 如果组已存在会抛 DuplicateGroupException，直接忽略即可
        try {
            stringRedisTemplate.opsForStream().createGroup("stream.orders", "g1");
        } catch (Exception e) {
            log.info("消费者组 'g1' 已存在，无需创建");
        }

        // 启动消费者线程
        SECKILL_ORDER_EXECUTOR.submit(() -> {
            while (running) {
                try {
                    List<MapRecord<String, Object, Object>> messages = stringRedisTemplate.opsForStream()
                            .read(Consumer.from("g1", "c1"),
                                    StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                                    StreamOffset.create("stream.orders", ReadOffset.lastConsumed()));

                    if (messages == null || messages.isEmpty()) {
                        continue;
                    }

                    for (MapRecord<String, Object, Object> message : messages) {
                        Map<Object, Object> value = message.getValue();
                        VoucherOrder voucherOrder = new VoucherOrder();
                        voucherOrder.setId(Long.valueOf(value.get("id").toString()));
                        voucherOrder.setUserId(Long.valueOf(value.get("userId").toString()));
                        voucherOrder.setVoucherId(Long.valueOf(value.get("voucherId").toString()));

                        handleVoucherOrder(voucherOrder);

                        stringRedisTemplate.opsForStream().acknowledge(
                                "stream.orders",
                                "g1",
                                message.getId());
                    }
                } catch (Exception e) {
                    if (isShuttingDown(e)) break;
                    log.error("消费订单消息异常", e);
                }
            }
        });
    }

    /**
     * 处理 pending 队列中未确认的消息。
     * XREADGROUP 读取消息后，如果还没 XACK 就宕机了，消息会留在 pending 列表。
     * 重启后用 ReadOffset.from("0") 读取 pending 消息，重新处理。
     */
    private void handlePendingOrders() {
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> messages = stringRedisTemplate.opsForStream()
                        .read(Consumer.from("g1", "c1"),
                                StreamReadOptions.empty().count(1),
                                StreamOffset.create("stream.orders", ReadOffset.from("0")));

                if (messages == null || messages.isEmpty()) {
                    break;
                }

                for (MapRecord<String, Object, Object> message : messages) {
                    Map<Object, Object> value = message.getValue();
                    VoucherOrder voucherOrder = new VoucherOrder();
                    voucherOrder.setId(Long.valueOf(value.get("id").toString()));
                    voucherOrder.setUserId(Long.valueOf(value.get("userId").toString()));
                    voucherOrder.setVoucherId(Long.valueOf(value.get("voucherId").toString()));

                    handleVoucherOrder(voucherOrder);

                    stringRedisTemplate.opsForStream().acknowledge(
                            "stream.orders",
                            "g1",
                            message.getId());
                }
            } catch (Exception e) {
                if (isShuttingDown(e)) break;
                log.error("处理 pending 队列异常", e);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private boolean isShuttingDown(Exception e) {
        if (!running) return true;
        Throwable t = e;
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && (msg.contains("STOPPING")
                    || msg.contains("destroyed")
                    || msg.contains("Connection closed")
                    || msg.contains("Connection refused"))) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * DB 层最终校验 + 落库（由消费线程调用）。
     * 作用：Redis Stream 的消息是可靠的，但极端情况下可能重复投递，
     * 所以这里用分布式锁 + SQL 条件更新做最后一道兜底。
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        RLock lock = redissonClient.getLock("order:" + userId);
        boolean locked = lock.tryLock();
        if (!locked) {
            log.error("用户{}已购券，重复下单被拦截", userId);
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
                if (count > 0) {
                    log.error("用户{}已存在券{}的订单", userId, voucherId);
                    status.setRollbackOnly();
                    return;
                }

                boolean success = seckillVoucherService.update()
                        .setSql("stock = stock - 1")
                        .eq("voucher_id", voucherId).gt("stock", 0)
                        .update();
                if (!success) {
                    log.error("券{}库存扣减失败", voucherId);
                    status.setRollbackOnly();
                    return;
                }

                save(voucherOrder);
            });
        } finally {
            lock.unlock();
        }
    }

    /**
     * 异步秒杀下单 —— 生产者。
     *
     * 流程：
     * 1. 生成全局唯一 orderId（雪花算法）
     * 2. 调用 Lua 脚本，原子完成：校验库存 → 校验一人一单 → 扣库存 → 记已购 → XADD 写 Stream
     * 3. 脚本返回 0 表示成功，直接返回 orderId 给用户
     * 4. 后台消费者线程从 Stream 中读取消息，异步落库
     *
     * 与旧版 BlockingQueue 方案的区别：
     * - 旧版：Lua 返回 0 → Java 构造 VoucherOrder → 塞入内存队列 → 消费者取队列 → 落库
     * - 新版：Lua 返回 0 → Java 直接返回 orderId（Stream 中的消息由 Lua 负责写入）
     *   好处：消息在 Redis 中持久化，不依赖 JVM 内存，不丢消息
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        // ① 先生成订单 ID，传入 Lua 脚本
        long orderId = redisIdWorker.nextId("order");

        // ② 调用 Lua 脚本（原子操作，包含校验 + XADD 写 Stream）
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );

        // ③ 根据脚本返回值快速拦截非法请求
        if (result != 0) {
            return Result.fail(result == 1 ? "库存不足" : "用户已下单");
        }

        // ④ 秒杀成功，直接返回 orderId
        //    订单落库由后台消费者线程异步完成
        return Result.ok(orderId);
    }

    // ========== 以下为旧版同步落库代码留存参考 ==========

    @Transactional
    @Override
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        long count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();
        if (count > 0) {
            return Result.fail("您已购买过该优惠券！");
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足！");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderId);
    }
}