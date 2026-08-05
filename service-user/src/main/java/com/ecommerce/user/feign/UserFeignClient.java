package com.ecommerce.user.feign;

import com.ecommerce.common.Result;
import com.ecommerce.user.dto.UserInfoResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 其他服务通过 Feign 调用用户服务
 * 使用方式：在目标服务中 @Autowired UserFeignClient userFeignClient;
 */
@FeignClient(name = "service-user", path = "/api/user")
public interface UserFeignClient {

    @GetMapping("/{id}")
    Result<UserInfoResponse> getById(@PathVariable("id") Long id);
}
