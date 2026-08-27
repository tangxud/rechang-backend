package com.rechang.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rechang.api.client.OcrClient;
import com.rechang.api.client.WechatLoginClient;
import com.rechang.api.dto.LoginDTO;
import com.rechang.api.dto.PhoneBindDTO;
import com.rechang.api.dto.RealnameDTO;
import com.rechang.api.entity.Attendee;
import com.rechang.api.entity.User;
import com.rechang.api.mapper.AttendeeMapper;
import com.rechang.api.mapper.UserMapper;
import com.rechang.api.vo.LoginVO;
import com.rechang.api.vo.RealnameResultVO;
import com.rechang.api.vo.UserProfileVO;
import com.rechang.common.exception.BusinessException;
import com.rechang.common.result.ResultCode;
import com.rechang.common.utils.HashUtils;
import com.rechang.common.utils.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final AttendeeMapper attendeeMapper;
    private final WechatLoginClient wechatClient;
    private final OcrClient ocrClient;

    public LoginVO login(LoginDTO dto) {
        Map<String, Object> session = wechatClient.code2session(dto.getCode());
        String openid = (String) session.get("openid");
        String unionid = (String) session.get("unionid");

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getOpenid, openid));
        boolean isNewUser = false;
        if (user == null) {
            user = new User();
            user.setOpenid(openid);
            user.setUnionid(unionid);
            user.setNickname(dto.getNickname());
            user.setAvatarUrl(dto.getAvatarUrl());
            user.setRealnameStatus("UNVERIFIED");
            userMapper.insert(user);
            isNewUser = true;
        }

        String token = JwtUtils.generateToken(user.getId(), openid);

        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setExpiresIn(JwtUtils.getExpirationSeconds());
        vo.setUserId(user.getId());
        vo.setNewUser(isNewUser);
        vo.setNeedPhone(user.getPhone() == null || user.getPhone().isBlank());
        vo.setNeedRealname(!"VERIFIED".equals(user.getRealnameStatus()));
        return vo;
    }

    public String bindPhone(PhoneBindDTO dto, Long userId) {
        User user = getUserById(userId);
        String phone;
        if (dto.getPhone() != null && !dto.getPhone().isBlank()) {
            phone = dto.getPhone();
        } else {
            phone = wechatClient.decryptPhone(dto.getEncryptedData(), dto.getIv());
        }
        user.setPhone(phone);
        userMapper.updateById(user);
        return HashUtils.maskPhone(phone);
    }

    public RealnameResultVO submitRealname(RealnameDTO dto, Long userId) {
        User user = getUserById(userId);

        Map<String, String> ocrResult = ocrClient.recognizeIdCard(dto.getIdCardFrontUrl(), dto.getIdCardBackUrl());
        String realName = ocrResult.get("name");
        String idCardNo = ocrResult.get("id_card_no");

        String idCardHash = HashUtils.sha256(idCardNo);
        String idCardMasked = HashUtils.maskIdCard(idCardNo);

        user.setRealnameStatus("VERIFIED");
        user.setRealnameTime(new Date());
        userMapper.updateById(user);

        Attendee existingSelf = attendeeMapper.selectOne(
                new LambdaQueryWrapper<Attendee>()
                        .eq(Attendee::getUserId, userId)
                        .eq(Attendee::getIsSelf, 1));
        if (existingSelf == null) {
            Attendee attendee = new Attendee();
            attendee.setUserId(userId);
            attendee.setAttendeeName(realName);
            attendee.setIdCardHash(idCardHash);
            attendee.setIdCardMasked(idCardMasked);
            attendee.setIsSelf(1);
            attendeeMapper.insert(attendee);
        }

        RealnameResultVO vo = new RealnameResultVO();
        vo.setRealName(HashUtils.maskName(realName));
        vo.setIdCardMasked(idCardMasked);
        vo.setStatus("VERIFIED");
        return vo;
    }

    public RealnameResultVO getRealnameStatus(Long userId) {
        User user = getUserById(userId);
        RealnameResultVO vo = new RealnameResultVO();
        vo.setStatus(user.getRealnameStatus());
        if ("VERIFIED".equals(user.getRealnameStatus())) {
            Attendee self = attendeeMapper.selectOne(
                    new LambdaQueryWrapper<Attendee>()
                            .eq(Attendee::getUserId, userId)
                            .eq(Attendee::getIsSelf, 1));
            if (self != null) {
                vo.setRealName(HashUtils.maskName(self.getAttendeeName()));
                vo.setIdCardMasked(self.getIdCardMasked());
            }
        }
        return vo;
    }

    public UserProfileVO getUserProfile(Long userId) {
        User user = getUserById(userId);
        UserProfileVO vo = new UserProfileVO();
        vo.setUserId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setPhone(user.getPhone() != null ? HashUtils.maskPhone(user.getPhone()) : null);
        vo.setRealnameStatus(user.getRealnameStatus());
        vo.setNeedPhone(user.getPhone() == null || user.getPhone().isBlank());
        vo.setNeedRealname(!"VERIFIED".equals(user.getRealnameStatus()));
        if ("VERIFIED".equals(user.getRealnameStatus())) {
            Attendee self = attendeeMapper.selectOne(
                    new LambdaQueryWrapper<Attendee>()
                            .eq(Attendee::getUserId, userId)
                            .eq(Attendee::getIsSelf, 1));
            if (self != null) {
                vo.setRealName(HashUtils.maskName(self.getAttendeeName()));
                vo.setIdCardMasked(self.getIdCardMasked());
            }
        }
        vo.setCreatedAt(user.getCreateTime());
        return vo;
    }

    public UserProfileVO updateProfile(Long userId, String nickname, String avatarUrl) {
        User user = getUserById(userId);
        if (nickname != null && !nickname.isBlank()) {
            user.setNickname(nickname);
        }
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            user.setAvatarUrl(avatarUrl);
        }
        userMapper.updateById(user);
        return getUserProfile(userId);
    }

    private User getUserById(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return user;
    }
}
