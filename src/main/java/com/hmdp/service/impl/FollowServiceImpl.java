package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.BeanUtils;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import com.sun.org.apache.xpath.internal.operations.Bool;
import io.reactivex.internal.operators.observable.ObservableSwitchIfEmpty;
import netscape.security.UserDialogHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 栗子ing
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IUserService userService;

    @Override
    public Result followed(Long id, Boolean isFollow) {
        //获取当前用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        //判断是否已经关注
        if (BooleanUtil.isTrue(isFollow)) {
            //如果没有关注，关注 添加数据库
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            boolean isSuccess = save(follow);
            //如果更新数据库成功
            if (isSuccess) {
                stringRedisTemplate.opsForSet().add(key, follow.getFollowUserId().toString());
            }
        } else {
            //如果已经关注， 取消关注， 删除数据库
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", id));
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, id.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result checkFollow(Long id) {
        //获取当前用户id
        Long userId = UserHolder.getUser().getId();

        //去数据库查看是否已经关注
        Integer count = query().eq("user_id", userId).eq("follow_user_id", id).count();

        return Result.ok(count > 0);
    }

    /**
     * 查看共同好友
     * @param id
     * @return
     */
    @Override
    public Result followCommon(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        String key2 = "follows:" + id;

        Set<String> strings = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (strings == null || strings.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //解析出所有id
        List<Long> ids = strings.stream().map(Long::valueOf).collect(Collectors.toList());

        //查询所有用户
        List<UserDTO> userDTOS = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        //最后封装返回
        return Result.ok(userDTOS);
    }
}
