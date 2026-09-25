package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.val;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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

    // 代金券秒杀
    // @param voucherId 代金券id
    // @return 结果数据
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
        synchronized (userId.toString().intern()) {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    // 创建代金券订单
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单
        Long userId = UserHolder.getUser().getId();

        // 5.1查询订单
        int count = query().eq("user_id", userId) // select count(*) from voucher_order where user_id = ? and voucher_id = ?
                .eq("voucher_id", voucherId)
                .count();

        // 5.2判断用户是否已购买
        if (count > 0) {
            return Result.fail("您已购买过该优惠券！");
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") //update stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0) //where voucher_id = ? and stock > 0
                .update();
        if (!success) {
            return Result.fail("库存不足！");
        }

        // 7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();

        // 7.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        // 7.2用户id
        voucherOrder.setUserId(userId);

        // 7.3优惠券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 8.返回订单id
        return Result.ok(orderId);
    }
}
