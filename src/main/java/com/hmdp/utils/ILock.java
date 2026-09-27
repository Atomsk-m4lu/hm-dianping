package com.hmdp.utils;

public interface ILock {

    // 尝试获取锁
    boolean tryLock(long timeoutSec);

    // 解锁
    void unlock();
}
