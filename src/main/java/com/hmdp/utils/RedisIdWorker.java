package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * Created with IntelliJ IDEA.
 *
 * @Author 栗子ing
 * @Date 2023/9/2 17:00
 * @description: 生成全局唯一id
 */
@Component
public class RedisIdWorker {

    private StringRedisTemplate stringRedisTemplate;

    /**
     * 初始时间
     */
    private static final long START_TIME = 1693674120L;

    private static final int TIME_BIT = 32;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 根据前缀生成全局唯一id
     * @param keyPrefix
     * @return
     */
    public long nextId(String keyPrefix) {
        //1、首先获取当前时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - START_TIME;
        //2、然后在获取序列号
        //2.1 获取当前时间，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));

        //2.2 自增长
        long increment = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        System.out.println("时间戳：" + Long.toBinaryString(timestamp) + " 自增：" + Long.toBinaryString(increment));
        //3、最后拼接返回
        return timestamp << TIME_BIT | increment;
    }

}
