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
    public Result<UserInfoResponse> getById(@RequestAttribute("userId") Long currentUserId,
                                             @PathVariable Long id) {
        // 仅允许查看自己的信息，防止 IDOR 越权
        if (!currentUserId.equals(id)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        User user = userService.getById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        UserInfoResponse resp = new UserInfoResponse();
        resp.setId(user.getId());
        resp.setUsername(user.getUsername());
        resp.setPhone(user.getPhone());
        resp.setEmail(user.getEmail());
        resp.setAvatar(user.getAvatar());
        return Result.success(resp);
    }
}
