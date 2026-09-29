package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 查询热门探店
     * @param current 当前页
     * @return 热门探店列表
     */
    Result queryHotBlog(Integer current);

    /**
     * 根据id查询探店博文
     * @param id 探店博文id
     * @return 探店博文
     */
    Result getBlogById(Long id);

    /**
     * 点赞探店博文
     * @param id 探店博文id
     * @return 点赞结果
     */
    Result likeBlog(Long id);

    /**
     * 查询探店博文点赞点赞数量
     * @param id 探店博文id
     * @return 点赞数量
     */
    Result getBlogLikes(Long id);

    /**
     * 新增探店博文
     * @param blog 探店博文
     * @return 探店博文id
     */
    Result saveBlog(Blog blog);
}