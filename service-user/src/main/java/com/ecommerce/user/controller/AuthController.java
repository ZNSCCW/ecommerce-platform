package com.ecommerce.user.controller;

import com.ecommerce.common.Result;
import com.ecommerce.user.dto.*;
import com.ecommerce.user.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Result<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(authService.register(request));
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @PostMapping("/refresh")
    public Result<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return Result.success(authService.refreshToken(request.getRefreshToken()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("Authorization") String authHeader,
                                @Valid @RequestBody RefreshTokenRequest request) {
        String accessToken = authHeader.replace("Bearer ", "");
        authService.logout(accessToken, request.getRefreshToken());
        return Result.success();
    }

    @GetMapping("/info")
    public Result<UserInfoResponse> userInfo(@RequestAttribute("userId") Long userId) {
        return Result.success(authService.getUserInfo(userId));
    }
}
