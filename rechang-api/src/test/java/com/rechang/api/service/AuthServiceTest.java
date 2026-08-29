package com.rechang.api.service;

import com.rechang.api.client.OcrClient;
import com.rechang.api.client.WechatLoginClient;
import com.rechang.api.dto.LoginDTO;
import com.rechang.api.dto.PhoneBindDTO;
import com.rechang.api.dto.RealnameDTO;
import com.rechang.api.entity.Attendee;
import com.rechang.api.entity.User;
import com.rechang.api.mapper.AttendeeMapper;
import com.rechang.api.mapper.UserMapper;
import com.rechang.api.support.Fixtures;
import com.rechang.api.vo.LoginVO;
import com.rechang.common.exception.BusinessException;
import com.rechang.common.utils.HashUtils;
import com.rechang.common.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录/绑定手机/实名认证（OCR 与微信能力经接口 mock）。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserMapper userMapper;
    @Mock AttendeeMapper attendeeMapper;
    @Mock WechatLoginClient wechatClient;
    @Mock OcrClient ocrClient;
    @Mock WechatSessionKeyStore wechatSessionKeyStore;
    @InjectMocks AuthService authService;

    @BeforeEach
    void jwtSecretIsolation() {
        JwtUtils.initSecret("auth-service-test-secret-0123456789abcdef");
    }

    private User user(long id, String phone, String realnameStatus) {
        User u = new User();
        u.setId(id);
        u.setOpenid("openid-" + id);
        u.setPhone(phone);
        u.setRealnameStatus(realnameStatus);
        return u;
    }

    /* ================= login ================= */

    @Test
    @DisplayName("新用户首登：落库 UNVERIFIED + 发 token + needPhone/needRealname 引导")
    void loginNewUser() {
        LoginDTO dto = new LoginDTO();
        dto.setCode("wx-code");
        dto.setNickname("新用户");
        when(wechatClient.code2session("wx-code")).thenReturn(Map.of(
                "openid", "open-1", "session_key", "k", "unionid", "u1"));
        when(userMapper.selectOne(any())).thenReturn(null);

        LoginVO vo = authService.login(dto);

        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(cap.capture());
        assertThat(cap.getValue().getOpenid()).isEqualTo("open-1");
        assertThat(cap.getValue().getRealnameStatus()).isEqualTo("UNVERIFIED");
        assertThat(vo.isNewUser()).isTrue();
        assertThat(vo.isNeedPhone()).isTrue();
        assertThat(vo.isNeedRealname()).isTrue();
        assertThat(vo.getUserId()).isEqualTo(cap.getValue().getId());
        assertThat(JwtUtils.getUserId(vo.getToken())).isEqualTo(vo.getUserId());
        verify(wechatSessionKeyStore).save(cap.getValue().getId(), "k");
    }

    @Test
    @DisplayName("老用户登录：不重复建档，按已填信息决定引导项")
    void loginExistingUser() {
        when(wechatClient.code2session("wx-code")).thenReturn(Map.of("openid", "openid-1", "unionid", ""));
        User existing = user(Fixtures.USER_A, "13888888888", "VERIFIED");
        when(userMapper.selectOne(any())).thenReturn(existing);

        LoginDTO dto = new LoginDTO();
        dto.setCode("wx-code");
        LoginVO vo = authService.login(dto);

        verify(userMapper, never()).insert(any(User.class));
        assertThat(vo.isNewUser()).isFalse();
        assertThat(vo.isNeedPhone()).isFalse();
        assertThat(vo.isNeedRealname()).isFalse();
    }

    /* ================= bindPhone ================= */

    @Test
    @DisplayName("显式手机号优先于微信解密通道")
    void bindPhoneExplicit() {
        User u = user(Fixtures.USER_A, null, "UNVERIFIED");
        when(userMapper.selectById(Fixtures.USER_A)).thenReturn(u);
        PhoneBindDTO dto = new PhoneBindDTO();
        dto.setPhone("13999990000");

        String masked = authService.bindPhone(dto, Fixtures.USER_A);
        assertThat(masked).isEqualTo("139****0000");
        verify(wechatClient, never()).decryptPhone(any(), any(), any());
    }

    @Test
    @DisplayName("无显式手机号走微信解密（取 Redis 中的 session_key 传给解密通道）")
    void bindPhoneDecryptFallback() {
        User u = user(Fixtures.USER_A, null, "UNVERIFIED");
        when(userMapper.selectById(Fixtures.USER_A)).thenReturn(u);
        when(wechatSessionKeyStore.load(Fixtures.USER_A)).thenReturn("sk");
        when(wechatClient.decryptPhone("sk", "enc", "iv")).thenReturn("13888888888");
        PhoneBindDTO dto = new PhoneBindDTO();
        dto.setEncryptedData("enc");
        dto.setIv("iv");

        assertThat(authService.bindPhone(dto, Fixtures.USER_A)).isEqualTo("138****8888");
    }

    @Test
    @DisplayName("登录未返回 session_key（老用户场景）时以 null 透传，由 store 端忽略不写 Redis")
    void loginWithoutSessionKeySkipsStore() {
        when(wechatClient.code2session("wx-code")).thenReturn(Map.of("openid", "openid-1", "unionid", ""));
        when(userMapper.selectOne(any())).thenReturn(user(Fixtures.USER_A, "13888888888", "VERIFIED"));

        LoginDTO dto = new LoginDTO();
        dto.setCode("wx-code");
        authService.login(dto);

        verify(wechatSessionKeyStore).save(Fixtures.USER_A, null);
    }

    /* ================= 实名认证 ================= */

    private RealnameDTO realnameDto() {
        RealnameDTO dto = new RealnameDTO();
        dto.setIdCardFrontUrl("https://cdn.rechang.com/front.jpg");
        dto.setIdCardBackUrl("https://cdn.rechang.com/back.jpg");
        return dto;
    }

    @Test
    @DisplayName("实名成功：用户 VERIFIED + 幂等创建 is_self 观演人 + 返回脱敏")
    void submitRealnameCreatesSelfAttendee() {
        User u = user(Fixtures.USER_A, null, "UNVERIFIED");
        when(userMapper.selectById(Fixtures.USER_A)).thenReturn(u);
        when(ocrClient.recognizeIdCard("https://cdn.rechang.com/front.jpg", "https://cdn.rechang.com/back.jpg"))
                .thenReturn(Map.of("name", "张三明", "id_card_no", "330102199001010012"));
        when(attendeeMapper.selectOne(any())).thenReturn(null);

        var vo = authService.submitRealname(realnameDto(), Fixtures.USER_A);

        assertThat(u.getRealnameStatus()).isEqualTo("VERIFIED");
        assertThat(u.getRealnameTime()).isNotNull();

        ArgumentCaptor<Attendee> cap = ArgumentCaptor.forClass(Attendee.class);
        verify(attendeeMapper).insert(cap.capture());
        assertThat(cap.getValue().getIsSelf()).isEqualTo(1);
        assertThat(cap.getValue().getIdCardHash()).isEqualTo(HashUtils.sha256("330102199001010012"));

        assertThat(vo.getStatus()).isEqualTo("VERIFIED");
        assertThat(vo.getRealName()).isEqualTo("张*明");
        assertThat(vo.getIdCardMasked()).isEqualTo("3301**********0012");
    }

    @Test
    @DisplayName("已存在 is_self 观演人时不重复创建")
    void submitRealnameIdempotent() {
        User u = user(Fixtures.USER_A, null, "UNVERIFIED");
        when(userMapper.selectById(Fixtures.USER_A)).thenReturn(u);
        when(ocrClient.recognizeIdCard(any(), any()))
                .thenReturn(Map.of("name", "张三明", "id_card_no", "330102199001010012"));
        when(attendeeMapper.selectOne(any())).thenReturn(
                Fixtures.attendee(9L, Fixtures.USER_A, "张三明", "hash", 1));

        authService.submitRealname(realnameDto(), Fixtures.USER_A);
        verify(attendeeMapper, never()).insert(any(Attendee.class));
    }

    @Test
    @DisplayName("实名状态查询：UNVERIFIED 只回状态")
    void realnameStatusUnverified() {
        when(userMapper.selectById(Fixtures.USER_A)).thenReturn(user(Fixtures.USER_A, null, "UNVERIFIED"));
        var vo = authService.getRealnameStatus(Fixtures.USER_A);
        assertThat(vo.getStatus()).isEqualTo("UNVERIFIED");
        assertThat(vo.getRealName()).isNull();
    }

    @Test
    @DisplayName("用户不存在 → USER_NOT_FOUND")
    void userNotFound() {
        when(userMapper.selectById(Fixtures.USER_A)).thenReturn(null);
        assertThatThrownBy(() -> authService.getRealnameStatus(Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1001);
    }

    @Test
    @DisplayName("getUserProfile: 实名用户带脱敏姓名，needXxx 为 false")
    void userProfileVerified() {
        User u = user(Fixtures.USER_A, "13888888888", "VERIFIED");
        when(userMapper.selectById(Fixtures.USER_A)).thenReturn(u);
        lenient().when(attendeeMapper.selectOne(any())).thenReturn(
                Fixtures.attendee(9L, Fixtures.USER_A, "张三明", "hash", 1));

        var vo = authService.getUserProfile(Fixtures.USER_A);
        assertThat(vo.getPhone()).isEqualTo("138****8888");
        assertThat(vo.getNeedPhone()).isFalse();
        assertThat(vo.getNeedRealname()).isFalse();
        assertThat(vo.getRealName()).isEqualTo("张*明");
    }

    @Test
    @DisplayName("updateProfile: 空白昵称/头像不覆盖")
    void updateProfileIgnoresBlank() {
        User u = user(Fixtures.USER_A, null, "UNVERIFIED");
        u.setNickname("原名");
        u.setAvatarUrl("old.png");
        when(userMapper.selectById(Fixtures.USER_A)).thenReturn(u);

        authService.updateProfile(Fixtures.USER_A, "  ", "");
        assertThat(u.getNickname()).isEqualTo("原名");
        assertThat(u.getAvatarUrl()).isEqualTo("old.png");
    }
}
