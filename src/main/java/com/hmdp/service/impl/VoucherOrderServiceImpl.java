package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.val;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
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

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    public void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHander());
    }

    private class VoucherOrderHander implements Runnable {

        @Override
        public void run(){
            while(true){
                try {
                    // 1.从阻塞队列中获取订单
                    VoucherOrder voucherOrder = orderTasks.take();

                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.info("处理订单异常", e);
                }
            }

        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1.获取用户id
        Long userId = voucherOrder.getUserId();

        // 2.创建锁对象
        RLock lock = redissonClient.getLock("order:" + userId);

        // 3.获取锁
        boolean success = lock.tryLock();

        // 4.判断是否获取成功
        if (!success) {
            log.error("用户{}已购买过，不允许重复下单！", userId);
            return;
        }

        // 5.创建代金券订单
        try {
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 5.2解锁
            lock.unlock();
        }
    }

     private IVoucherOrderService proxy;
    // 代金券秒杀
    // @param voucherId 代金券id
    // @return 结果数据
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        // 1.调用lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );

        // 2.判断结果是否为0
        if (result != 0) {
            // 2.1不为0的话代表没有购买资格
            return Result.fail(result == 1 ? "库存不足" : "用户已下单");
        }

        // 3.为0的话代表有购买资格，把下单信息保存到阻塞队列中
        VoucherOrder voucherOrder = new VoucherOrder();

        // 3.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        // 3.2用户id
        voucherOrder.setUserId(userId);

        // 3.3优惠券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 3.4放入阻塞队列中
        orderTasks.add(voucherOrder);

        // 4.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 5.返回订单id
        return Result.ok(0);
    }

/*
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("优惠券不存在");
        }

        // 2.判断秒杀是否开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀未开始！");
        }

        // 3.判断优惠券是否过期
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束！");
        }

        // 4.判断秒杀券库存是否充足
        if (seckillVoucher.getStock() <= 0) {
            return Result.fail("库存不足！");
        }
        Long userId = UserHolder.getUser().getId();

        // 5.创建锁对象
        // SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        RLock lock = redissonClient.getLock("order:" + userId);

        // 6.获取锁
        boolean success = lock.tryLock();

        // 7.判断是否获取成功
        if (!success) {
            return Result.fail("当前用户已购买过，不允许重复下单！");
        }

        // 8.创建代金券订单
        try {
            // 8.1调用服务层方法创建订单
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 8.2解锁
            lock.unlock();
        }
    }
 */

    // 创建代金券订单
    @Transactional
    @Override
    public Result createVoucherOrder(Long voucherId) {
        // 1.一人一单
        Long userId = UserHolder.getUser().getId();

        // 1.1查询订单
        int count = query().eq("user_id", userId) // select count(*) from voucher_order where user_id = ? and voucher_id = ?
                .eq("voucher_id", voucherId)
                .count();

        // 1.2判断用户是否已购买
        if (count > 0) {
            return Result.fail("您已购买过该优惠券！");
        }

        // 2.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") //update stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0) //where voucher_id = ? and stock > 0
                .update();
        if (!success) {
            return Result.fail("库存不足！");
        }

        // 3.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();

        // 3.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        // 3.2用户id
        voucherOrder.setUserId(userId);

        // 3.3优惠券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 4.返回订单id
        return Result.ok(orderId);
    }
}
