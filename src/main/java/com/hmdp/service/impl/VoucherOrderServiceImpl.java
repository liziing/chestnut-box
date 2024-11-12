package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import jdk.nashorn.internal.ir.IfNode;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.management.remote.rmi._RMIConnection_Stub;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 栗子
 *
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //创建一个线程池
    private static final ExecutorService SECKILL_ORDER = Executors.newSingleThreadExecutor();
    //会在类初始化之前执行
    @PostConstruct
    private void init() {
        SECKILL_ORDER.submit(new VoucherOrderHandler());
    }

    //创建线程任务
    private class VoucherOrderHandler implements Runnable {

        String streamName = "stream.orders";

        @Override
        public void run() {

            while (true) {
                try {
                    //1、获取消息队列信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders  >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(streamName, ReadOffset.lastConsumed())
                    );
                    if (list == null || list.isEmpty()) {
                        //2、获取失败，就重新获取
                        continue;
                    }
                    //3、获取成功，保存订单,创建订单
                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    //4、最后确认信息XACK
                    stringRedisTemplate.opsForStream().acknowledge(streamName,"g1", entries.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    //发生异常,需要到pendingList中获取确认
                    handlerStreamGroup();
                }
            }
        }

        private void handlerStreamGroup() {
            while (true) {
                try {
                    //1、获取pending-List信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders  0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(streamName, ReadOffset.from("0"))
                    );
                    if (list == null || list.isEmpty()) {
                        //2、获取失败，说明pendingList中没有数据了，结束
                        break;
                    }
                    //3、获取成功，保存订单,创建订单
                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    //4、最后确认信息XACK
                    stringRedisTemplate.opsForStream().acknowledge(streamName,"g1", entries.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    //出现异常不需要递归调用，因为上面会while循环一直获取
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }

        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            //获取用户id
//            Long userId = UserHolder.getUser().getId();   //因为这是不同线程，所以需要直接获取
            Long userId = voucherOrder.getUserId();
            //获取锁对象
            RLock lock = redissonClient.getLock("lock:order:" + userId);
            //尝试获取锁
            boolean flag = lock.tryLock();
            if (!flag) {
                //如果获取不到锁
                log.error("不允许重复下单");
                return;
            }
            try {
                //注意：由于是spring的事务是放在threeadLocal中，此时的是多线程，事务会失效
                proxy.createVoucherOrder(voucherOrder);
            } finally {
                //释放锁
                lock.unlock();
            }
        }
    }


    //创建一个阻塞队列
    /*private BlockingQueue<VoucherOrder> blockingQueue = new ArrayBlockingQueue<>(1024 * 1024);
    //创建线程任务  用于处理线程池处理的任务   ----
    //因为秒杀开始随时都有可能有人下单，所以需要在类启动之前加载
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {

            while (true) {
                try {
                    //循环获取阻塞队列中的数据
                    VoucherOrder voucherOrder = blockingQueue.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }

        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            //获取用户id
//            Long userId = UserHolder.getUser().getId();   //因为这是不同线程，所以需要直接获取
            Long userId = voucherOrder.getUserId();
            //获取锁对象
            RLock lock = redissonClient.getLock("lock:order:" + userId);
            //尝试获取锁
            boolean flag = lock.tryLock();
            if (!flag) {
                //如果获取不到锁
                log.error("不允许重复下单");
                return;
            }
            try {
                //注意：由于是spring的事务是放在threeadLocal中，此时的是多线程，事务会失效
                proxy.createVoucherOrder(voucherOrder);
            } finally {
                //释放锁
                lock.unlock();
            }
        }
    }*/


    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");
        //1、执行lua脚本
        Long isSuccess = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId));
        int r = isSuccess.intValue();
        //2、返回值如果不等于0，返回错误
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //因为这里不是多线程模式，所以需要提前获取
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
    }
   /* @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //1、执行lua脚本
        Long isSuccess = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());
        int r = isSuccess.intValue();
        //2、返回值如果不等于0，返回错误
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //3、返回值如果是0  返回订单号
        long orderId = redisIdWorker.nextId("order");
        //4、将数据封装起来
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        //5、将订单id跟用户id存放在阻塞队列中，等待异步执行
        blockingQueue.add(voucherOrder);

        //因为这里不是多线程模式，所以需要提前获取
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
    }*/

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //首先获取优惠卷信息
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断优惠时间开始或者结束
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀还没开始！");
//        }
//
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束!");
//        }
//
//        //判断库存是否还有
//        if (voucher.getStock() < 1) {
//            return Result.fail("优惠卷已经被抢光了");
//        }
//        Long userId = UserHolder.getUser().getId();
//
//        //获取锁对象
////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock simpleRedisLock = redissonClient.getLock("lock:order:" + userId);
//
//        //尝试获取锁
//        boolean isLock = simpleRedisLock.tryLock();
//        if (!isLock) {
//            //如果获取锁失败
//            return Result.fail("不能重复下单！");
//        }
//        try {
//            //拿到当前对象的代理对象(跟事务有关的代理对象)
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            //然后通过代理对象调用这个方法，就会被spring管理了，因为是spring创建的，他是带有事务的一个函数
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            simpleRedisLock.unlock();
//        }
//
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //判断用户是否存在该用户
        int count = query().eq("user_id", voucherOrder.getUserId()).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //如果存在就删减库存
        if (count > 0) {
            log.error("用户已经购买过了");
            return;
        }
        //修改库存
        boolean b = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).update();
        if (!b) {
            log.error("库存不足");
            return;
        }
        //最后添加订单
        save(voucherOrder);
    }


//    @Transactional
//    public Result createVoucherOrder(Long VoucherId) {
//        //然后去数据库查看当前用户是否已经抢过
//        Long userId = UserHolder.getUser().getId();
//        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//
//        if (count > 0) {
//            return Result.fail("您已经抢过了");
//        }
//
//        //扣减库存
//        boolean flag = seckillVoucherService.update()
//                .setSql("stock = stock - 1")
//                .eq("voucher_id", voucherId).gt("stock", 0)
//                .update();
//
//        if (!flag) {
//            return Result.fail("更新数据库失败");
//        }
//        //然后封装订单信息
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //设置全局唯一id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        voucherOrder.setVoucherId(voucherId);
//        voucherOrder.setUserId(UserHolder.getUser().getId());
//
//        //写入数据库
//        save(voucherOrder);
//        //最后返回
//        return Result.ok(orderId);
//    }
}
