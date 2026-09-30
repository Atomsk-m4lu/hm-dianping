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
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);

        // 9. 返回token
        return Result.ok(token);

    }

    // 签到功能
    @Override
    public Result sign() {
        // 1. 获取登录用户
        Long userId = UserHolder.getUser().getId();

        // 2. 获取当前时间
        LocalDateTime now = LocalDateTime.now();

        // 3. 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyy/MM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + ":" + keySuffix;

        // 4. 获取今天是这个月的第几天
        int dayOfMonth = now.getDayOfMonth();

        // 5. 写入redis
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);

        // 6. 返回ok
        return Result.ok();
    }

    // 统计连续签到天数
    @Override
    public Result signCount() {
        // 1. 获取登录用户
        Long userId = UserHolder.getUser().getId();

        // 2. 获取今天日期
        LocalDate today = LocalDate.now();

        // 3. 初始化连续签到计数
        int count = 0;

        // 4. 从今天开始，向前逐天检查签到记录
        LocalDate date = today;
        while (true) {
            // 4.1 拼接当前日期对应的key（按月存）
            String keySuffix = date.format(DateTimeFormatter.ofPattern("yyyy/MM"));
            String key = RedisConstants.USER_SIGN_KEY + userId + ":" + keySuffix;

            // 4.2 获取当天在BitMap中的偏移量（从0开始）
            int dayOfMonth = date.getDayOfMonth();

            // 4.3 检查该位是否为1
            Boolean signed = stringRedisTemplate.opsForValue().getBit(key, dayOfMonth - 1);

            if (signed == null || !signed) {
                // 未签到，结束循环
                break;
            }

            // 已签到，计数+1，继续检查前一天
            count++;
            date = date.minusDays(1);
        }

        // 5. 返回连续签到天数
        return Result.ok(count);
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