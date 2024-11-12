package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.img.gif.NeuQuant;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import io.netty.util.internal.StringUtil;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.matcher.CollectionSizeMatcher;
import net.sf.jsqlparser.expression.LongValue;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.security.Key;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 栗子ing
 */
@Service
@Slf4j
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.extracted(blog);
            this.checkIsLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBolgInfo(Long id) {
        //查询笔记
        Blog blog = getById(id);
        if (blog == null) {
            //如果为空
            return Result.fail("笔记不存在!");
        }
        //查询用户
        extracted(blog);
        //判断是否已经点过赞了
        checkIsLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 判断是不是点过赞了
     * @param blog
     */
    private void checkIsLiked(Blog blog) {
        //获取用户id
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        Long userId = user.getId();
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.ok();
        }
        Long userId = user.getId();

        String key = RedisConstants.BLOG_LIKED_KEY + id;
        //1、判断当前用户是否已经点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //2、如果没有点过
        if (score == null) {
            //2.1 进行点赞，更新数据库
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //2.2 然后更新redis
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            //3、如果已经更新过
            //3.1 取消点赞，更新数据库
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //3.2 移除redis
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询用户的点赞排行榜
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        //1、首先查询用户前5的用户
        Set<String> range = stringRedisTemplate.opsForZSet().range(key, 0, 4);

        //空指针
        if (range == null) {
            return Result.ok(Collections.emptyList());
        }

        log.info("点赞排行榜，{}", range);
        List<Long> ids = new ArrayList<>();
        for (String s : range) {
            ids.add(Long.parseLong(s));
        }
        String idsStr = CollectionUtil.join(ids, ",");
        //2、然后查询对应的用户   WHERE id IN ( 7, 8, 1 ) ORDER BY FIELD(id, 7, 8, 1);
        List<User> users = userService.query().in("id", ids).last("ORDER BY FIELD(id, " + idsStr + ")").list();

        log.info("点赞排行榜，{}", users);
        //3、最后转成DTO返回
        List<UserDTO> userDTOS = new ArrayList<>();
        for (User user : users) {
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            userDTOS.add(userDTO);
        }
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean save = save(blog);
        if (!save) {
            return Result.ok("新增失败");
        }
        //首先需要获取所有的粉丝用户 select * from follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //然后推送到每一个粉丝的收件箱中
        for (Follow follow: follows) {
            //每一个粉丝都有单独的收件箱
            String key = "feed:" + follow.getUserId();
            //推送到粉丝的收件箱
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }

        // 返回id
        return Result.ok(blog.getId());
    }
    //滚动分页查询
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1、获取当前用户的收件箱的key
        Long userId = UserHolder.getUser().getId();

        String key = RedisConstants.FEED_KEY + userId;
        //2、进行滚动分页查询
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);

        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }

        //2.1 查询到的数据需要进行解析
        long minTime = 0;
        int offsetCnt = 1;
        //指定大小，会有默认长度 ？
        List<Long> ids = new ArrayList<>(typedTuples.size());
        for (ZSetOperations.TypedTuple<String> tuple: typedTuples) {
            //2.1.1 取出id 跟 最小时间
            String id = tuple.getValue();
            ids.add(Long.valueOf(id));

            long time = tuple.getScore().longValue();
            if (time == minTime) {
                offsetCnt ++;
            } else {
                minTime = time;
                offsetCnt = 1;
            }
        }

        //2.2 查询所有的id的blog 笔记
        //拼接所有id
        String idStr = StrUtil.join(", ", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id, " + idStr + ")").list();
        //因为这里查询到的只有笔记的基本信息，需要查询点赞的人跟排行榜之类的数据
        for (Blog blog : blogs) {
            //查询用户
            extracted(blog);
            //判断是否已经点过赞了
            checkIsLiked(blog);
        }
        //3、最后封装返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(offsetCnt);

        return Result.ok(scrollResult);
    }

    private void extracted(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
    }
}
