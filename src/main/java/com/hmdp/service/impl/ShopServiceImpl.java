package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        //缓存穿透 Shop shop=queryWithPassThrough(id);
        //互斥锁解决缓存击穿问题 Shop shop = queryWithMutex(id);
        //逻辑过期解决缓存击穿问题
        Shop shop=queryWithLogicalExpire(id);
        if (shop == null) {return Result.fail("店铺不存在");}
        return Result.ok(shop);
    }


    /*设置锁*/
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /*释放锁*/
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }



/*---
* 更新商铺信息
* @param shop
* @return
* */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }



    /*-----缓存穿透
     *
     * */

    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回这里的命中结果是非null的
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        //命中是否是空值，这里在前面只有两种情况一种是null一种是空字符串，不是null说明是空字符串，说明店铺不存在
        if(shopJson!=null){
            return null;
        }
        // 4.不存在，根据id查询数据库
        Shop shop = getById(id);
        // 5.不存在，返回错误
        if (shop == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6.存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7.返回
        return shop;
    }

    /*------缓存击穿
    *
    * */

    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回这里的命中结果是非null的
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //命中是否是空值，这里在前面只有两种情况一种是null一种是空字符串，不是null说明是空字符串，说明店铺不存在
        if(shopJson!=null){
            return null;
        }

        // ------------------------------4.实现缓存重建
        // 4.1.获取互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        Shop shop = null;
        try {
            boolean flag = tryLock(lockKey);
            // 4.2.判断是否获取到锁
            if(!flag){
                // 4.3 失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.成功，根据id查询数据库
            shop = getById(id);
            //模拟重建缓存的时间
            Thread.sleep(200);
            // 5.不存在，返回错误
            if (shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 6.存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7.释放互斥锁
            unlock(lockKey);
        }
        // 8.返回
        return shop;
    }



    /*------缓存预热
    *
    * @param id 店铺id
    * @param expireTime 过期时间，单位秒
    * */
    public void saveShop2Redis(Long id,Long expireTime) throws InterruptedException {
        //1.查询店铺数据
        Shop shop=getById(id);
        //模拟重建缓存的延迟，越长越容易出现线程安全问题，休眠200毫秒(200毫秒内多个线程由于锁未释放一直返回旧数据)
        Thread.sleep(200);
        //2.封装逻辑过期时间
        RedisData redisdata=new RedisData();
        redisdata.setData(shop);
        redisdata.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        //3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id, JSONUtil.toJsonStr(redisdata));

    }

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 3.不存在，直接返回空
            return null;
        }

        //4.命中，需要将json字符串转换为对象
        RedisData redisdata = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisdata.getData();//从RedisData中获取的是JSONObject类型，由于可能不同的对象，我们先转换为JSONObject
        Shop shop = JSONUtil.toBean(data, Shop.class);//将JSONObject转换为Shop对象
        LocalDateTime expireTime = redisdata.getExpireTime();

        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1未过期，返回店铺对象
            return shop;
        }

        //5.2已过期，需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean flag = tryLock(lockKey);

        //6.2判断是否获取到锁---获取锁成功需要再次检测redis缓存是否过期，如果过期，需要重新缓存重建，否则直接返回店铺对象
        if (flag) {
            //6.3 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放互斥锁
                    unlock(lockKey);
                }
            });
        }

        //6.4返回过期店铺对象
        return shop;
    }


}



