package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Created with IntelliJ IDEA.
 *
 * @Author 栗子ing
 * @Date 2023/8/27 16:13
 * @description:
 */

/**
 * 第一个拦截器，拦截所有路径，根据token去Redis中判断是否存在该用户信息，判断用户是否登录
 * 如果登录，存放到throwLocal中
 */
public class RefreshInterceptor implements HandlerInterceptor {

    //因为这个类是我们自己创建的，所以不能使用注解来注入，需要使用构造器来导入
    private StringRedisTemplate stringRedisTemplate;

    public RefreshInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //首先判断当前session是不是存在用户
        //Object user = request.getSession().getAttribute(SystemConstants.USER_NICK_NAME_PREFIX);
        //从请求头中获取对应的token请求头
        String token = request.getHeader("authorization");
        //如果请求头是空，直接抛出异常
        if (token == null) {
            return true;
        }
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);

        //如果不存在就返回错误
        if (map.isEmpty()) {
            return true;
        }
        //使用工具转成bean对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(map, new UserDTO(), false);

//        Long id = (Long) map.get("id");
//        userDTO.setId(id);
//        userDTO.setNickName((String) map.get("nickName"));
//        userDTO.setIcon((String) map.get("icon")) ;

        //如果存在就将他放到throwLocal中
        UserHolder.saveUser(userDTO);

        //如果用户还在活跃状态，需要更新redis存活时间   刷新存活时间
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //最后执行完毕需要清楚用户
        UserHolder.removeUser();
    }
}
