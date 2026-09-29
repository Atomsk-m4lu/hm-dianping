package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    /**
     * 关注/取消关注
     * @param followUserId 关联的用户id
     * @param isFollow 是否关注
     * @return
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    /**
     * 判断是否关注
     * @param followUserId 关联的用户id
     * @return
     */
    @GetMapping("/or/not/{id}")
    public Result follow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    /**
     * 获取共同关注列表
     * @return
     */
    @GetMapping("/common/{id}")
    public Result commonFollow(@PathVariable("id") Long id) {
        return followService.commonFollow(id);
    }



}