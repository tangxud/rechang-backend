package com.rechang.api.config;

import com.rechang.api.service.TicketService;
import com.rechang.common.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 启动时将配置中的签名密钥注入静态工具类：
 * <ul>
 *   <li>jwt.secret —— 用户登录令牌（HS256，至少 32 字节）</li>
 *   <li>qr.hmac-secret —— 检票二维码 HMAC 签名</li>
 * </ul>
 * 未配置时空值透传、工具类保留内置 dev 默认；test/prod profile 已去除默认占位，缺失即启动失败。
 * 密钥管理与轮换规范见工作区仓库 docs/design/environment_config.md。
 */
@Configuration
public class AppSecretConfig {

    public AppSecretConfig(@Value("${jwt.secret:}") String jwtSecret,
                           @Value("${qr.hmac-secret:}") String qrHmacSecret) {
        JwtUtils.initSecret(jwtSecret);
        TicketService.initHmacSecret(qrHmacSecret);
    }
}
