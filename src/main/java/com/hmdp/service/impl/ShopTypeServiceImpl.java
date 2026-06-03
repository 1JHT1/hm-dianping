package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /*店铺类型查询缓存*/
    @Override
    public List<ShopType> queryTypeList() {
        String key= RedisConstants.CACHE_SHOPTYPE_KEY;
        //从redis查询店铺类型缓存
        String shopJason=stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if(StrUtil.isNotBlank(shopJason)){
            return JSONUtil.toList(shopJason,ShopType.class);//存在转成List直接返回
        }//不存在，查询数据库
        List<ShopType> typeList = this.query().orderByAsc("sort").list();
        if(typeList.isEmpty()){
            return new ArrayList<>();//不存在返回空列表
        }
        //存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList),RedisConstants.CACHE_SHOPTYPE_TTL, TimeUnit.MINUTES);
        //最后返回查询结果
        return typeList;

    }
}
