package com.ecommerce.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关鉴权配置（application.yml 的 {@code gateway.auth.*}）。
 */
@Component
@ConfigurationProperties(prefix = "gateway.auth")
public class GatewayAuthProperties {

    /** 总开关，联调时可临时关掉 */
    private boolean enabled = true;

    /** 与 service-user 的 AuthService 保持一致的黑名单 key 前缀 */
    private String blacklistPrefix = "token:blacklist:";

    /**
     * 匿名白名单，格式 {@code "METHOD /ant/path/**"}，方法支持 {@code *}。
     * 只放行「不需要登录」的接口；其余默认要求 Bearer Token。
     */
    private List<String> whitelist = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBlacklistPrefix() {
        return blacklistPrefix;
    }

    public void setBlacklistPrefix(String blacklistPrefix) {
        this.blacklistPrefix = blacklistPrefix;
    }

    public List<String> getWhitelist() {
        return whitelist;
    }

    public void setWhitelist(List<String> whitelist) {
        this.whitelist = whitelist;
    }
}
