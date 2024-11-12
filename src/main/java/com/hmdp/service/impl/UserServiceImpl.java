package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.lang.model.element.VariableElement;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 栗子ing
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplatel;
    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号是否正确
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);
        if (phoneInvalid) {
            //如果不正确，返回错误
            return Result.fail("手机号码格式错误");
        }

        //如果正确，那么就发送验证码
        String code = RandomUtil.randomNumbers(6);

        log.info("手机验证码发送成功：{}", code);

        //然后将验证码存到session中，
        //session.setAttribute(phone, code);
        //将号码存到redis中
        stringRedisTemplatel.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        return Result.ok();
    }

    /**
     * 登录
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号是否正确
        String phone = loginForm.getPhone();
        //校验手机号是否正确
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);
        //然后判断当前是否正确
        //String code = (String) session.getAttribute(phone);
        //从redis中获取code
        String code = stringRedisTemplatel.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        //如果有一个不正确，就返回错误
        if (phoneInvalid) {
            return Result.fail("手机号格式错误！");
        }
        if (code == null || !code.equals(loginForm.getCode())) {
            return Result.fail("验证码错误");
        }

        //如果正确，那么就到数据库判断是不是存在当前的用户
        LambdaQueryWrapper<User> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(User::getPhone, phone);
        User user = userService.getOne(lambdaQueryWrapper);

        if (user == null) {
            //如果用户不存在，就新建用户
            user = createNewUser(phone);
        }
        //最后通过生成一个随机的token来存储用户的信息
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        String token = UUID.randomUUID().toString(true);
        //Map<String, Object> mapToken = BeanUtil.beanToMap(userDTO); //会出现类型转换异常long -> string

        //自己构造一个map来存储，需要全部都是string类型
        Map<String, String> mapToken = new HashMap<>();
        mapToken.put("id", userDTO.getId().toString());
        mapToken.put("nickName", userDTO.getNickName());
        mapToken.put("icon", userDTO.getIcon());

        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;

        stringRedisTemplatel.opsForHash().putAll(tokenKey, mapToken);
        stringRedisTemplatel.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
//        session.setAttribute(SystemConstants.USER_NICK_NAME_PREFIX,);

        //然后返回给前端一个token，
        return Result.ok(token);
    }

    /**
     * 创建用户
     * @param phone
     * @return
     */
    private User createNewUser(String phone) {
        User user = new User();
        user.setNickName("user_" + "" + RandomUtil.randomString(10));
        user.setPhone(phone);
        boolean save = userService.save(user);
        log.info("save = {}" + save);
        return user;
    }

    @Override
    public Result getUserInfo(Long id) {
        // 查询详情
        User user = userService.getById(id);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.ok(userDTO);
    }

    @Override
    public Result sign() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取当前时间
        LocalDateTime now = LocalDateTime.now();
        String date = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //拼接key

        String key = RedisConstants.USER_SIGN_KEY + userId + date;
        //然后获取今天是第几天
        int dayOfMonth = now.getDayOfMonth();
        //最后写入redis

        stringRedisTemplatel.opsForValue().setBit(key,dayOfMonth - 1,true);
        return Result.ok();
    }


    @Override
    public Result signCount() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取当前时间
        LocalDateTime now = LocalDateTime.now();
        String preFix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //获取key
        String key = RedisConstants.USER_SIGN_KEY + userId + preFix;
        //获取当前是第几天
        int dayOfMonth = now.getDayOfMonth();
        //然后获取本月第0天到今天的签到数据   返回的是一个十进制数
        List<Long> results = stringRedisTemplatel.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));

        //判断当前获取到的是不是空
        if (results == null || results.isEmpty()) {
            return Result.ok(0);
        }

        Long data = results.get(0);
        if (data == null || data == 0) {
            return Result.ok(0);
        }
        //连续签到数量
        int count = 0;
        while (true) {
            //进行与运算
            if ((data & 1) == 0) {
                //如果当前是0 那么直接返回
                break;
            } else {
                //如果是1，计数器++
                count ++;
            }
            //每一次与运算结束，需要进行右移一位
            data >>>= 1;
        }
        return Result.ok(count);
    }
}
