package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 发送短信验证码并保存验证码
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 若不符合格式，返回错误信息
            return Result.fail("手机号格式错误");
        }

        // 3. 生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4. 保存验证码到redis
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5. 发送短信验证码
        log.info("发送短信验证码：{}", code);

        // 6. 返回ok
        return Result.ok();
    }

    // 登录功能
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 若不符合格式，返回错误信息
            return Result.fail("手机号格式错误");
        }

        // 3. 从redis中获取验证码并校验验证码
        String code = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if (!loginForm.getCode().equals(code) || code == null) {
            // 4. 若验证码不一致，返回错误信息
            return Result.fail("验证码错误");
        }

        // 5. 一致，根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();

        // 6.判断用户是否存在
        if (user == null) {
            // 7. 若用户不存在，注册新用户并保存到数据库
            user = createUserWithPhone(phone);
        }

        // 8. 保存用户到redis
        // 8.1 随机生成token作为登录令牌
        String token = UUID.randomUUID().toString(true);

        // 8.2 转为hash存储到redis中
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        // 将map中的所有值转为String，避免StringRedisTemplate序列化异常
        Map<String, String> stringUserMap = new HashMap<>();
        userMap.forEach((key, value) -> {
            if (value != null) {
                stringUserMap.put(key, value.toString());
            }
        });
        stringRedisTemplate.opsForHash().putAll(tokenKey, stringUserMap);
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 9. 返回token
        return Result.ok(token);

    }

    private User createUserWithPhone(String phone) {
        // 1. 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        // 2. 保存用户
        save(user);
        return user;
    }
}