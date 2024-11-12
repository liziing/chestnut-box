package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 栗子ing
 */
public interface IFollowService extends IService<Follow> {

    Result followed(Long id, Boolean isFollow);

    Result checkFollow(Long id);

    Result followCommon(Long id);
}
