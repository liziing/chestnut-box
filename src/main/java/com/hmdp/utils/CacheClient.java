package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import io.lettuce.core.GeoArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.events.Event;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.UnknownFormatConversionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Created with IntelliJ IDEA.
 *
 * @Author 栗子ing
 * @Date 2023/8/29 18:14
 * @description: redis封装工具类
 */
@Slf4j
@Component
public class CacheClient {

    private StringRedisTemplate stringRedisTemplate;

    //创建一个线程池  有十个线程
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public void set(String key, Object value, Long ttl, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), ttl, unit);
    }

    //将任意Java对象序列化为json并存储在stirng类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
    public void setWithLogicalExpire(String key, Object value, Long ttl, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(ttl)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值方式解决缓存穿透问题
    public <R, ID> R queryWithPassThrough(String keyPre, ID id, Function<ID, R> dpMysql, Long ttl, TimeUnit unit, Class<R> type) {
        String key = keyPre + id;
        //首先到缓存中找是否存在
        String json = stringRedisTemplate.opsForValue().get(key);
        //如果存在 直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        //如果不存在
        //看看是不是空值，如果是直接返回null
        if (json != null) {
            return null;
        }
        //最后在到数据库中找，
        R r = dpMysql.apply(id);
        //如果没找到，设置为空值，然后返回null
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //如果找到了，设置到redis中
        this.set(key,JSONUtil.toJsonStr(r), ttl, unit);
        //最后返回
        return r;
    }


    public <R, ID> R queryWithLogicalExpire(String keyPre, ID id, Class<R> type, Function<ID, R> dbMysql, Long ttl, TimeUnit unit) {
        String key = keyPre + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //如果当前redis缓存中找不到，直接返回null
        if (StrUtil.isBlank(json)) {
            return null;
        }

        //如果找到了，判断当前有没有过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        //如果没有过期，直接返回旧数据
        if (expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        //如果过期了，尝试获取互斥锁
        //获取失败
        //直接返回旧数据
        String keyLock = RedisConstants.LOCK_SHOP_KEY + id;
        boolean flag = tryLock(keyLock);
        //获取成功了， 重新判断有没有缓存有没有过期
        if (flag) {
            //如果现在缓存没有过期，直接返回
            String jsonDoubleCheck = stringRedisTemplate.opsForValue().get(key);
            RedisData redisData1 = JSONUtil.toBean(jsonDoubleCheck, RedisData.class);
            LocalDateTime expireTime1 = redisData1.getExpireTime();
            if (expireTime1.isAfter(LocalDateTime.now())) {
                return JSONUtil.toBean((JSONObject) redisData1.getData(), type);
            }
            //如果过期了，
            // 开一个单独的线程，进行数据重构
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R r1 = dbMysql.apply(id);
                    //重新写入redis
                    this.setWithLogicalExpire(key, r1, ttl, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    delLock(keyLock);
                }
            });
        }
        //最后返回旧数据
        return r;
    }

    //互斥锁解决缓存击穿
    public <R, ID> R queryWithMutex(String keyPre, ID id, Class<R> type, Function<ID, R> dbMysql, Long ttl, TimeUnit unit) {
        //redis中查找是否存在
        String key = keyPre + id;
        //如果存在直接返回
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json,type);
        }
        //如果不是空值，直接返回
        if (json != null) {
            return null;
        }

        String keyLock = RedisConstants.LOCK_SHOP_KEY + id;
        //尝试获取互斥锁
        boolean b = tryLock(keyLock);
        R r1 = null;
        try {
            //如果获取失败，休眠一段时间，重新调用
            if (!b){
                Thread.sleep(50L);
                return queryWithMutex(keyPre, id,type,dbMysql,ttl,unit);
            }

            //如果获取成功，那么重新判断当前redis中是否存在
            String jsonDoubleCheck = stringRedisTemplate.opsForValue().get(key);
            //如果存在直接返回
            R r = JSONUtil.toBean(jsonDoubleCheck, type);
            if (r != null) {
                return r;
            }

            //不存在进行数据重构
            r1 = dbMysql.apply(id);
            if (r1 == null) {
                //如果是数据库也没查到，赋空值
                this.set(key,"", ttl,unit);
                return null;
            }

            this.set(key,r1,ttl,unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            delLock(keyLock);
        }

        //最后返回数据
        return r1;
    }


    /**
     * 获取锁
     * @param keyLock
     * @return
     */
    private boolean tryLock(String keyLock) {
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(keyLock, "1");
        return aBoolean;
    }

    /**
     * 释放锁
     * @param keyLock
     */
    private void delLock(String keyLock) {
        stringRedisTemplate.delete(keyLock);
    }


}
