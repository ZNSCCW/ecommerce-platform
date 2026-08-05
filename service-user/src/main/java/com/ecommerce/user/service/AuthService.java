package com.ecommerce.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.common.BusinessException;
import com.ecommerce.common.JwtUtil;
import com.ecommerce.common.ResultCode;
import com.ecommerce.user.dto.*;
import com.ecommerce.user.entity.Permission;
import com.ecommerce.user.entity.Role;
import com.ecommerce.user.entity.User;
import com.ecommerce.user.entity.UserRole;
import com.ecommerce.user.mapper.PermissionMapper;
import com.ecommerce.user.mapper.RoleMapper;
import com.ecommerce.user.mapper.UserMapper;
import com.ecommerce.user.mapper.UserRoleMapper;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final RedisTemplate<String, Object> redisTemplate;

    /** Token 黑名单前缀 */
    private static final String TOKEN_BLACKLIST_PREFIX = "token:blacklist:";
    /** 验证码前缀 */
    private static final String CAPTCHA_PREFIX = "captcha:";

    /**
     * 用户注册
     */
    @Transactional
    public LoginResponse register(RegisterRequest request) {
        // 检查用户名是否已存在
        User existing = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, request.getUsername()));
        if (existing != null) {
            throw new BusinessException(ResultCode.USER_EXIST);
        }

        // 创建用户
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setPhone(request.getPhone());
        user.setEmail(request.getEmail());
        user.setStatus(1);

        try {
            userMapper.insert(user);
        } catch (DataIntegrityViolationException e) {
            // 高并发下唯一键冲突兜底（用户名唯一索引）
            throw new BusinessException(ResultCode.USER_EXIST);
        }

        // 查询普通用户角色（按 code 而非硬编码 id）
        Role userRole = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>()
                        .eq(Role::getCode, "USER"));
        if (userRole == null) {
            throw new BusinessException(500, "默认角色未配置，请联系管理员");
        }

        UserRole ur = new UserRole();
        ur.setUserId(user.getId());
        ur.setRoleId(userRole.getId());
        userRoleMapper.insert(ur);

        // 生成 Token
        return buildLoginResponse(user);
    }

    /**
     * 用户登录
     */
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, request.getUsername()));
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        if (user.getStatus() == 0) {
            throw new BusinessException(ResultCode.USER_DISABLED);
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.USER_PASSWORD_ERROR);
        }

        return buildLoginResponse(user);
    }

    /**
     * 刷新 Token
     */
    public LoginResponse refreshToken(String refreshToken) {
        // 检查是否在黑名单中
        if (Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_BLACKLIST_PREFIX + refreshToken))) {
            throw new BusinessException(ResultCode.TOKEN_INVALID);
        }

        Claims claims = JwtUtil.parseToken(refreshToken);
        String type = claims.get("type", String.class);
        if (!"refresh".equals(type)) {
            throw new BusinessException(ResultCode.TOKEN_INVALID);
        }

        Long userId = Long.parseLong(claims.getSubject());
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        return buildLoginResponse(user);
    }

    /**
     * 登出
     */
    public void logout(String accessToken, String refreshToken) {
        // 将 Token 加入黑名单（过期时间 = Token 剩余有效期）
        // accessToken 和 refreshToken 独立处理，互不影响
        blacklistToken(accessToken);
        blacklistToken(refreshToken);
    }

    /**
     * 将单个 Token 加入黑名单
     */
    private void blacklistToken(String token) {
        try {
            Claims claims = JwtUtil.parseToken(token);
            long expire = claims.getExpiration().getTime() - System.currentTimeMillis();
            if (expire > 0) {
                redisTemplate.opsForValue().set(TOKEN_BLACKLIST_PREFIX + token, "1",
                        expire, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            // Token 已过期无需加入黑名单
        }
    }

    /**
     * 获取当前用户信息
     */
    public UserInfoResponse getUserInfo(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        List<Role> roles = roleMapper.findByUserId(userId);
        List<String> roleNames = roles.stream().map(Role::getCode).collect(Collectors.toList());

        List<String> permissions = permissionMapper.findByUserId(userId)
                .stream().map(Permission::getPermission)
                .collect(Collectors.toList());

        UserInfoResponse resp = new UserInfoResponse();
        resp.setId(user.getId());
        resp.setUsername(user.getUsername());
        resp.setPhone(user.getPhone());
        resp.setEmail(user.getEmail());
        resp.setAvatar(user.getAvatar());
        resp.setRoles(roleNames);
        resp.setPermissions(permissions);
        return resp;
    }

    /**
     * 构建登录响应（生成双 Token）
     */
    private LoginResponse buildLoginResponse(User user) {
        List<Role> roles = roleMapper.findByUserId(user.getId());
        String roleCode = roles.isEmpty() ? "USER" : roles.get(0).getCode();

        String accessToken = JwtUtil.generateAccessToken(user.getId(), roleCode);
        String refreshToken = JwtUtil.generateRefreshToken(user.getId());

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userId(user.getId())
                .username(user.getUsername())
                .build();
    }
}
