package com.ecommerce.user.controller;

import com.ecommerce.common.Result;
import com.ecommerce.common.BusinessException;
import com.ecommerce.common.ResultCode;
import com.ecommerce.user.dto.UserInfoResponse;
import com.ecommerce.user.entity.User;
import com.ecommerce.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/{id}")
    public Result<UserInfoResponse> getById(@PathVariable Long id) {
        User user = userService.getById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        // 返回不含密码的用户信息
        UserInfoResponse resp = new UserInfoResponse();
        resp.setId(user.getId());
        resp.setUsername(user.getUsername());
        resp.setPhone(user.getPhone());
        resp.setEmail(user.getEmail());
        resp.setAvatar(user.getAvatar());
        return Result.success(resp);
    }
}
