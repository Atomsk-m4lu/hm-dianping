package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    /**
     * 查询热门探店
     * @param current 当前页
     * @return 热门探店列表
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            this.queryBlogUserInfo(blog);
            this.isBlogLiked(blog.getId(), blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result getBlogById(Long id) {
        // 查询blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("探店博文不存在");
        }
        
        // 查询跟blog关联的用户
        queryBlogUserInfo(blog);

        // 查询blog是否被点赞过
        isBlogLiked(id, blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Long id, Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，默认未点赞
            blog.setIsLike(false);
            return;
        }
        Long userId = user.getId();

        // 2.判断当前登录用户是否已点赞该博客
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 点赞探店博文
     * @param id 探店博文id
     * @return 点赞结果
     */
    @Override
    public Result likeBlog(Long id) {
        try {
            // 1.获取登录用户
            UserDTO user = UserHolder.getUser();
            if (user == null) {
                return Result.fail("用户未登录");
            }
            Long userId = user.getId();

            // 2.判断当前登录用户是否已点赞该博客
            String key = RedisConstants.BLOG_LIKED_KEY + id;
            Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
            if (score == null) {
                // 3.若未点赞，可以点赞
                // 3.1数据库点赞数+1
                boolean isSuccess = lambdaUpdate()
                        .setSql("liked = liked + 1")
                        .eq(Blog::getId, id)
                        .update();

                // 3.2保存用户到redis的zset集合中
                if (isSuccess) {
                    stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
                }
            } else {
                // 4.若已点赞，取消点赞
                // 4.1数据库点赞数-1
                boolean isSuccess = lambdaUpdate()
                        .setSql("liked = liked - 1")
                        .eq(Blog::getId, id)
                        .update();

                // 4.2从redis的zset集合中移除用户
                if (isSuccess) {
                    stringRedisTemplate.opsForZSet().remove(key, userId.toString());
                }
            }
            // 5.返回点赞结果
            return Result.ok();
        } catch (Exception e) {
            log.error("点赞失败，blogId={}", id, e);
            return Result.fail("点赞失败: " + e.getMessage());
        }
    }

    /**
     * 查询探店博文点赞点赞数量
     * @param id 探店博文id
     * @return 点赞数量
     */
    @Override
    public Result getBlogLikes(Long id) {
        // 1.查询top5点赞的用户
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        // 2.解析出其中用户id
        List<Long> ids = top5.stream().map(Long::parseLong).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);

        // 3.根据用户id查询用户
        List<User> users = userService
              .query()
              .in("id", ids)
              .last("ORDER BY FIELD(id, " + idStr + ")")
              .list();
		List<UserDTO> userDTOList = BeanUtil.copyToList(users, UserDTO.class);
		
		// 4.返回
		return Result.ok(userDTOList);
    }

    /**
     * 新增探店博文
     * @param blog 探店博文
     * @return 探店博文id
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());

        // 新增探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("新增探店博文失败");
        }

        // 查询笔记作者的所有粉丝：关注关系中 follow_user_id = 当前作者id
        List<Follow> followList = followService.query()
                .eq("follow_user_id", user.getId()).list();

        // 推送笔记id给所有粉丝
        for(Follow follow : followList){
            Long followerUserId = follow.getUserId();
            String key = RedisConstants.FEED_KEY + followerUserId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }

        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryFollowBlog(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();

        // 2.查询收件箱
        String key = RedisConstants.FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate
                .opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);

        // 3.非空判断
        if(tuples == null || tuples.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        // 4.解析数据
        List<Long> ids = new ArrayList<>(tuples.size());
        long minTime = max;
        int os = offset;
        for(ZSetOperations.TypedTuple<String> tuple : tuples){

            // 4.1获取id
            ids.add(Long.valueOf(tuple.getValue()));

            // 4.2获取分数(时间戳)
            long time = tuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else {
                minTime = time;
                os = 1;
            }

        }

        // 5.根据id查询blog
        List<Blog> blogList = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id, " + StrUtil.join(",", ids) + ")")
                .list();

        for(Blog blog : blogList){
            queryBlogUserInfo(blog);
            isBlogLiked(blog.getId(), blog);
        }

        // 6.封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogList);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

    /**
     * 查询博客关联的用户信息，并设置到博客对象中
     *
     * @param blog 博客对象，包含用户ID，方法执行后将设置用户名和头像
     */
    private void queryBlogUserInfo(Blog blog) {
        Long userId = blog.getUserId();
        if (userId == null) {
            blog.setName("匿名用户");
            return;
        }
        User user = userService.getById(userId);
        if (user != null) {
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        } else {
            blog.setName("用户已注销");
        }
    }
}