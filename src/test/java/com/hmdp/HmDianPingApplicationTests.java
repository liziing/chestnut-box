package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import javax.swing.*;
import java.security.Key;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private ShopServiceImpl shopService;

    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成500个线程
     */
    private ExecutorService executorService = Executors.newFixedThreadPool(500);
    @Test
    void testId() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i ++ ) {
                long id = redisIdWorker.nextId("order");
//                System.out.println("id =" + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i ++ ) {
            executorService.submit(task);

        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    @Test
    void testMy() {
        LocalDateTime now = LocalDateTime.now();
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long increment = stringRedisTemplate.opsForValue().increment("icr:" + "keyPrefix" + ":" + date);
        System.out.println(increment);
    }


    @Test
    void testRedis() {
        Shop shop = shopService.getById(1);
        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY + "1", shop,20L, TimeUnit.SECONDS);
    }

    @Test
    void loadGetData() {
        //获取所有店铺信息
        List<Shop> shops = shopService.list();

        //根据typeId类型进行分类
        Map<Long, List<Shop>> entries = shops.stream().collect(Collectors.groupingBy(shop -> shop.getTypeId()));

        //然后写入redis
        for (Map.Entry<Long, List<Shop>> entry : entries.entrySet()) {
            //获取店铺类型typeId
            Long typeId = entry.getKey();
            //获取指定的key
            String key = RedisConstants.SHOP_GEO_KEY + typeId;
            List<Shop> value = entry.getValue();

            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());

            //多次请求，影响性能
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }

    }

    @Test
    public void testHyLogLog() {

        String[] users = new String[1000];
        for (int i = 0; i < 1000000; i ++ ) {
            users[i % 1000] = "user:" + i;
            if (i % 1000 == 999) {
                stringRedisTemplate.opsForHyperLogLog().add("hll1", users);
            }
        }
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hll1");
        System.out.println("count = " + count);

    }


}











