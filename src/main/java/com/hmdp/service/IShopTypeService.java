package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 栗子ing
 */
public interface IShopTypeService extends IService<ShopType> {

    /**
     * 查询type数据
     * @return
     */
    Result queryTypeList();
}
