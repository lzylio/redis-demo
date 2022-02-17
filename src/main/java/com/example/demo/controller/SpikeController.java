package com.example.demo.controller;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用于测试redis秒杀
 */
@RestController
@RequestMapping("/api/spike")
@Slf4j
public class SpikeController {

    /**
     *  StringRedisTemplate和RedisTemplate的区别和选择
     *  https://blog.csdn.net/qq_39992641/article/details/97787443
     * 当你的redis数据库里面本来存的是字符串数据或者你要存取的数据就是字符串类型数据的时候，那么你就使用StringRedisTemplate即可，
     * 但是如果你的数据是复杂的对象类型，而取出的时候又不想做任何的数据转换，直接从Redis里面取出一个对象，那么使用RedisTemplate是更好的选择。
     * 就像get/post请求的区别一样，各有各的应用场景
     */


    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private AtomicInteger successNum = new AtomicInteger(0);

    private static final String LOCK_CAUTO = "cauto";

    @RequestMapping(value = "/initSku", method = RequestMethod.GET)
    public String initSku() {
        // 初始化商品数量和售卖数量
        stringRedisTemplate.opsForValue().set("product_sku", "5");
        successNum.set(0);
        Integer sku = Integer.parseInt(stringRedisTemplate.opsForValue().get("product_sku"));
        log.info("初始化库存数量：" + sku);
        return "初始化库存数量：" + sku;
    }

    @RequestMapping(value = "/successNum", method = RequestMethod.GET)
    public String successNum() {
        Integer sku = Integer.parseInt(stringRedisTemplate.opsForValue().get("product_sku"));
        log.info("顾客成功抢到的商品数量：" + successNum.get());
        log.info("库存数量：" + sku);
        return "顾客成功抢到的商品数量：" + successNum.get() + "</br>库存数量：" + sku;
    }

    /**
     * 会出现超卖情况的减少库存方式
     *
     * @return
     */
    @RequestMapping(value = "/reduceSku", method = RequestMethod.GET)
    public String reduceSku() {
        Integer sku = Integer.parseInt(stringRedisTemplate.opsForValue().get("product_sku"));
        sku = sku - 1;
        if (sku < 0) {
            log.info("当前线程:" + Thread.currentThread().getName() + ";库存不足");
            return "库存不足";
        }

        stringRedisTemplate.opsForValue().set("product_sku", sku.toString());

        log.info("当前线程：" + Thread.currentThread().getName() + ";减少库存成功,共减少" + successNum.incrementAndGet());

        return "调用成功";
    }

    // 通过加锁方式解决超卖问题(redisson)
    @SneakyThrows
    @RequestMapping(value = "/reduceSku4", method = RequestMethod.GET)
    public String reduceSku4() {
        RLock rLock = redissonClient.getLock(LOCK_CAUTO);
        try {
            // 加锁
            log.info("当前线程：" + Thread.currentThread().getName() + ";======等待锁======");

//            rLock.lock(50, TimeUnit.SECONDS);// 锁的有效时间，默认为30，这里设置为50方便观察-不确定有没有看门狗
            rLock.lock(60, TimeUnit.SECONDS);// 正常锁
            /*测试可重复锁 获取锁 start*/
            rLock.lock(60, TimeUnit.SECONDS);// 重入锁2 value + 1
            rLock.lock(60, TimeUnit.SECONDS);// 重入锁3 value + 1
            /*测试可重复锁 获取锁 end*/

//            rLock.lock();// 默认30秒，有看门狗，自动续期，生产一般使用这个

            log.info("当前线程：" + Thread.currentThread().getName() + ";======获取锁======");

            TimeUnit.SECONDS.sleep(5);// 加长获取锁时间，方便观察

            Integer sku = Integer.parseInt(stringRedisTemplate.opsForValue().get("product_sku"));
            sku = sku - 1;
            if (sku < 0) {
                log.info("当前线程:" + Thread.currentThread().getName() + ";库存不足");
                return "库存不足";
            }
            stringRedisTemplate.opsForValue().set("product_sku", sku.toString());
            log.info("当前线程：" + Thread.currentThread().getName() + ";减少库存成功,共减少" + successNum.incrementAndGet());
            return "调用成功";
        } finally {
            rLock.unlock();// 正常释放锁
            log.info("当前线程：" + Thread.currentThread().getName() + ";======释放锁======");

            /*测试可重复锁 释放锁 start*/
//            TimeUnit.SECONDS.sleep(2);
//            rLock.unlock();// 释放重入锁2 value - 1
//            log.info("当前线程：" + Thread.currentThread().getName() + ";======释放锁======");
//
//            TimeUnit.SECONDS.sleep(2);
//            rLock.unlock();// 释放重入锁3 value - 1
//            log.info("当前线程：" + Thread.currentThread().getName() + ";======释放锁======");
            /*测试可重复锁 释放锁 end*/
        }

    }


    /**
     * 加入事务的减少库存方式
     *
     * @return
     */
    @RequestMapping(value = "/reduceSku2", method = RequestMethod.GET)
    public String reduceSku2() {
        stringRedisTemplate.setEnableTransactionSupport(true);
        List<Object> results = stringRedisTemplate.execute(new SessionCallback<List<Object>>() {
            @Override
            public List<Object> execute(RedisOperations operations) throws DataAccessException {
                operations.watch("product_sku");
                String product_sku = (String) operations.opsForValue().get("product_sku");
                operations.multi();
                operations.opsForValue().get("product_sku");//必要的空查询
                Integer sku = Integer.parseInt(product_sku);
                sku = sku - 1;
                if (sku < 0) {
                    return null;
                }
                operations.opsForValue().set("product_sku", sku.toString());
                return operations.exec();
//                operations.unwatch(); //执行exec()后自动unwatch()

            }
        });

        if (results != null && results.size() > 0) {
            return "减少库存成功,共减少" + successNum.incrementAndGet();
        }

        return "库存不足";
//        return result.toString();
    }


    /**
     * 直接用jredis加入事务的减少库存方式
     *
     * @return
     */
    @RequestMapping(value = "/reduceSku3", method = RequestMethod.GET)
    public String reduceSku3() {
        Jedis jedis = new Jedis("127.0.0.1", 6379);
        List<Object> result ;
        Transaction transaction = null;
        try {
            jedis.watch("product_sku");
            int sku = Integer.parseInt(jedis.get("product_sku"));
            if (sku > 0) {
                transaction = jedis.multi();
                transaction.set("product_sku", String.valueOf(sku - 1));
//                int exp = 1/0;
                result = transaction.exec();
                if (result == null || result.isEmpty()) {
                    System.out.println("Transaction error...");// 可能是watch-key被外部修改，或者是数据操作被驳回
//                    transaction.discard();  //watch-key被外部修改时，discard操作会被自动触发
                    return "Transaction error...";
                }
            } else {
                return "库存不足";
            }
            return "减少库存成功,共减少" + successNum.incrementAndGet();
        } catch (Exception e) {
            log.error(e.getMessage());
            transaction.discard();
            return "fail";
        }
    }

}
