package com.rechang.api.client;

import com.rechang.common.exception.BusinessException;
import com.rechang.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 非 dev 环境占位实现：真实第三方服务未接入前，直接快速失败，
 * 避免静默返回 Mock 假数据流入测试/生产链路。
 */
@Slf4j
@Component
@Profile("!dev")
public class WechatLoginStub implements WechatLoginClient {

    @Override
    public Map<String, Object> code2session(String code) {
        throw new BusinessException(ResultCode.INTERNAL_ERROR, "微信登录未接入生产实现（当前环境非 dev）");
    }

    @Override
    public String decryptPhone(String encryptedData, String iv) {
        throw new BusinessException(ResultCode.INTERNAL_ERROR, "微信手机号解密未接入生产实现（当前环境非 dev）");
    }
}
