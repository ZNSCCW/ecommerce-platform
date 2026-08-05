package com.ecommerce.user.service;

import com.ecommerce.user.dto.UserInfoResponse;
import com.ecommerce.user.entity.User;
import com.ecommerce.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;

    public User getById(Long id) {
        return userMapper.selectById(id);
    }
}
