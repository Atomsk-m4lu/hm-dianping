package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 关注/取消关注
     * @param followUserId 关联的用户id
     * @param isFollow 是否关注
     * @return
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 判断是否关注
     * @param followUserId 关联的用户id
     * @return
     */
    Result isFollow(Long followUserId);

    /**
     * 获取共同关注列表
     * @param id 关联的用户id
     * @return
     */
    Result commonFollow(Long id);
}