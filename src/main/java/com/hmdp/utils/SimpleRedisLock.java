package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import com.sun.org.glassfish.external.statistics.Statistic;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Created with IntelliJ IDEA.
 *
 * @Author 栗子ing
 * @Date 2023/9/4 15:21
 * @description:
 */

public class SimpleRedisLock implements ILock{

    //每一个业务对应一把锁
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_LOCK = "lock:";
    private static final String ID_Prefix = UUID.fastUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 获取锁
     * @param TimeOut
     * @return
     */
    @Override
    public boolean tryLock(Long TimeOut) {
        //获取当前线程的id    UUID + 当前线程id
        String threadId = ID_Prefix + Thread.currentThread().getId();
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(KEY_LOCK + name, threadId, TimeOut, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(aBoolean);
    }

    @Override
    public void unlock() {
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_LOCK + name),
                ID_Prefix + Thread.currentThread().getId()
                );
    }
//    @Override
//    public void unlock() {
//        //释放锁
//        String threadId = ID_Prefix + Thread.currentThread().getId();
//        //判断当前锁的值是不是发生了改变
//        String id = stringRedisTemplate.opsForValue().get(KEY_LOCK + name);
//        if (threadId.equals(id)) {
//            stringRedisTemplate.delete(KEY_LOCK + name);
//        }
//    }
}
