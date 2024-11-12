package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 栗子ing
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Autowired
    private IFollowService followService;

    /**
     * 关注 取消关注
     * @param id
     * @param isFollow
     * @return
     */
    @PutMapping("/{id}/{isFollowed}")
    public Result followed(@PathVariable("id") Long id, @PathVariable("isFollowed") Boolean isFollow) {
        return followService.followed(id, isFollow);
    }

    /**
     * 判断是否已经关注了
     * @param id
     * @return
     */
    @GetMapping("/or/not/{id}")
    public Result checkFollow(@PathVariable("id") Long id) {
        return followService.checkFollow(id);
    }

    @GetMapping("/common/{id}")
    public Result followCommon(@PathVariable Long id) {
        return followService.followCommon(id);
    }
}
