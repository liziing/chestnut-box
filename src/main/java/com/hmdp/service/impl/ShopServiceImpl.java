package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonToken;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import com.sun.org.apache.bcel.internal.generic.RETURN;
import io.netty.util.internal.StringUtil;
import org.apache.logging.log4j.util.Strings;
import org.apache.tomcat.util.buf.UDecoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.management.remote.rmi._RMIConnection_Stub;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 栗子ing
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @Override
    public Result queryShopById(Long id) {
        //缓存穿透代码
        //queryShopThough(id);
        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, this::getById, 1L, TimeUnit.MINUTES, Shop.class);

        //缓存击穿代码————互斥锁
        //Shop shop = queryCacheLock(id);
        //Shop shop = queryWithLogicalExpire(id);
        //Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, 1L, TimeUnit.MINUTES);
        //如果返回为null， 返回错误信息
        if (shop == null) {
            return Result.fail("店铺信息不存在！");
        }

        return Result.ok(shop);
    }

    /**
     * 尝试获取互斥锁
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        //设置一个过期时间，防止死锁的情况
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁
     * @param key
     * @return
     */
    private void delLock(String key) {
        stringRedisTemplate.delete(key);
    }

    //创建一个线程池  有十个线程
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 缓存击穿代码——逻辑过期
     * @param id
     * @return
     */
    //因为使用逻辑过期的一般都是热key，所以缓存中已经有了该数据
    //不存在找不到的情况，不需要判断空
    public Shop queryWithLogicalExpire(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //1.从redis中找到该缓存数据
        String jsonShop = stringRedisTemplate.opsForValue().get(key);
        // 如果没有找到数据
        if (StrUtil.isBlank(jsonShop)) {
            //直接返回null， 表示没找到
            return null;
        }
        //如果找到
        //需要将json转成对应的类
        RedisData redisData = JSONUtil.toBean(jsonShop, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        //2.如果没有过期，直接返回
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        //3.如果过期尝试获取互斥锁
        boolean isLock = tryLock(lockKey);
        //4、如果获取失败
        if (!isLock) {
            //4.1 直接返回旧的数据
            return shop;
        }
        //5.1 如果获取成功
        //5.2 开启新线程进行数据重构
        //5.3 拿到锁的时候进行二次判断，当前数据是否过期，如果没有过期直接返回
        String jsonShop1 = stringRedisTemplate.opsForValue().get(key);
        RedisData redisData1 = JSONUtil.toBean(jsonShop1, RedisData.class);
        LocalDateTime expireTime1 = redisData1.getExpireTime();
        //如果没有过期，直接返回数据
        if (expireTime1.isAfter(LocalDateTime.now())) {
            return JSONUtil.toBean((JSONObject) redisData1.getData(),  Shop.class);
        }

        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                //直接调用数据重构的方法
                this.saveShop2Redis(id, RedisConstants.CACHE_SHOP_TTL);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                //最后需要将释放锁
                delLock(lockKey);
            }
        });

        //最后返回过期商铺数据
        return shop;
    }

    /**
     * 缓存击穿代码 ——互斥锁解决
     * @param id
     * @return
     */
    public Shop queryCacheLock(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY;
        //首先去缓存中查询
        String json = stringRedisTemplate.opsForValue().get(key + id);
        //如果存在，直接返回
        if (StrUtil.isNotBlank(json)) {
            Shop shop = JSONUtil.toBean(json, Shop.class);
            return shop;
        }
        //如果查不到数据  说明是=”“  空值
        if (json != null) {
            return null;
        }

        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        //如果到了这里说明redis没有当前数据，所以尝试获取锁
        boolean flag = tryLock(lockKey);
        Shop shop = null;
        try {
            //如果获取锁失败，休眠一段时间，在重新访问
            if (!flag) {
                Thread.sleep(50);
                //然后递归调用重新去查询数据，然后返回查询到的shop
                return queryCacheLock(id);
            }

            //如果获取锁成功，需要检查一下缓存是否存在，如果存在世界就可以返回了
            //因为是多线程的原因，如果当前线程是因为等待刚好在获取锁的时候，别人刚好释放了锁
            //此时缓存中已经有了值，所以直接查就可以
            String jsonDoubleCheck = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(jsonDoubleCheck)) {
                return JSONUtil.toBean(jsonDoubleCheck,Shop.class);
            }

            //模拟高并发的延时
            Thread.sleep(200);
            //如果获取锁成功
            //进行数据重建
            //如果不存在，就去数据库中查找
            shop = this.getById(id);
            //如果输入数据错误，返回错误
            if (shop == null) {
                //如果数据库没有找到，说明是参数有误，缓存空值
                stringRedisTemplate.opsForValue().set(key + id,"", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //写入redis    设置过期时间
            stringRedisTemplate.opsForValue().set(key + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //最后释放锁
            delLock(lockKey);
        }

        //最后返回
        return shop;
    }

    /**
     * 缓存穿透代码
     * @param id 商铺id
     * @return
     */
    public Shop queryShopThough(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY;
        //首先去缓存中查询
        String json = stringRedisTemplate.opsForValue().get(key + id);
        //如果存在，直接返回
        if (StrUtil.isNotBlank(json)) {
            Shop shop = JSONUtil.toBean(json, Shop.class);
            return shop;
        }
        //如果查不到数据
        if (json != null) {
            return null;
        }

        //如果不存在，就去数据库中查找
        Shop shop = this.getById(id);
        //如果输入数据错误，返回错误
        if (shop == null) {
            //如果数据库没有找到，说明是参数有误，缓存空值
            stringRedisTemplate.opsForValue().set(key + id,"", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //写入redis    设置过期时间
        stringRedisTemplate.opsForValue().set(key + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //最后返回
        return shop;
    }

    /**
     * 逻辑缓存/缓存重建
     */
    public void saveShop2Redis(Long id, Long ttl) {
        //从数据库中店铺信息
        Shop shop = this.getById(id);

        //设置过期时间
        LocalDateTime localDateTime = LocalDateTime.now().plusSeconds(ttl);
        //封装数据
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(localDateTime);
        //然后存到redis中
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));

    }

    @Override
    @Transactional   //如果删除缓存失败，需要进行事务回滚
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        //如果id不存在
        if (id == null) {
            return Result.fail("商品的id有误");
        }
        //更新数据库
        updateById(shop);
        //删除redis缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        //返回
        return Result.ok();
    }


    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1、首先判断是否需要根据距离查询
        if (x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //获取当前类型的key
        String key = RedisConstants.SHOP_GEO_KEY + typeId;

        //获取分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        //2、查询redis中相关的数据，查询shopId 和 距离 分页
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().radius(
                key,
                new Circle(new Point(x, y), new Distance(5000)),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().limit(end)
        );

        //如果没有查到数据，直接返回空
        if (results == null) {
            return Result.ok();
        }

        //3、解析id

        //获取真正的所有数据
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();

        if (from >= content.size()) {
            return Result.ok();
        }

        List<Long> ids = new ArrayList<>(content.size());
        Map<String, Double> distance = new HashMap<>(content.size());
        // 4、截取
        content.stream().skip(from).forEach(result -> {
            //获取shopId
            String idStr = result.getContent().getName();
            ids.add(Long.valueOf(idStr));

            //获取距离
            double value = result.getDistance().getValue();
            distance.put(idStr, value);
        });

        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        //5、查询出所有用户，并且封装距离 返回
//        String idStr = StrUtil.join(", ", ids);
//        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id, " + idStr + ")").list();
        //距离
        for (Shop shop : shops) {
            shop.setDistance(distance.get(shop.getId().toString()));
        }

        return Result.ok(shops);
    }
}
