package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import io.lettuce.core.GeoArgs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 栗子ing
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 查询类型数据
     * @return
     */
    @Override
    public Result queryTypeList() {
        String key = RedisConstants.TYPE_LIST_KEY;
        //首先从redis中查
        List<String> typeList = stringRedisTemplate.opsForList().range(key, 0, -1);
        //如果存在直接返回
        if (!typeList.isEmpty()) {
            List<ShopType> shopTypes = new ArrayList<>();
            for (String type : typeList) {
                ShopType shopType = JSONUtil.toBean(type, ShopType.class);
                shopTypes.add(shopType);
            }
            return Result.ok(shopTypes);
        }
        //不存在去数据库中查找
        List<ShopType> typeList1 = this.query().orderByAsc("sort").list();
        //最后写到redis中
        for (ShopType shopType : typeList1) {
            stringRedisTemplate.opsForList().leftPush(key,JSONUtil.toJsonStr(shopType));
        }

        //封装返回
        return Result.ok(typeList1);
    }
}
