package com.hmdp.dto;

import lombok.Data;

/**
 * 避免信息泄漏，登录时只返回用户的基本信息
 */
@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
}
