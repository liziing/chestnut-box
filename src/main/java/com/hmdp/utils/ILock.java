package com.hmdp.utils;

/**
 * Created with IntelliJ IDEA.
 *
 * @Author 栗子ing
 * @Date 2023/9/4 15:20
 * @description:
 */
public interface ILock {

    /**
     * 尝试获取锁
     * @param TimeOut
     * @return
     */
    boolean tryLock(Long TimeOut);

    /**
     * 释放锁
     */
    void unlock();
}
