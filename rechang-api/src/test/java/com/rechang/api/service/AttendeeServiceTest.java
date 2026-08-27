package com.rechang.api.service;

import com.rechang.api.dto.AttendeeDTO;
import com.rechang.api.mapper.AttendeeMapper;
import com.rechang.api.support.Fixtures;
import com.rechang.api.vo.AttendeeVO;
import com.rechang.common.exception.BusinessException;
import com.rechang.common.utils.HashUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 观演人 CRUD：身份证校验、同人重复、越权守卫。
 */
@ExtendWith(MockitoExtension.class)
class AttendeeServiceTest {

    private static final String VALID_ID = "110101199003070011";

    @Mock AttendeeMapper attendeeMapper;
    @InjectMocks AttendeeService attendeeService;

    private AttendeeDTO dto(String name, String idCardNo) {
        AttendeeDTO dto = new AttendeeDTO();
        dto.setName(name);
        dto.setIdCardNo(idCardNo);
        return dto;
    }

    @Test
    @DisplayName("create: 身份证格式错误拒绝")
    void createInvalidIdCard() {
        assertThatThrownBy(() -> attendeeService.create(dto("张三", "123"), Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1004);
    }

    @Test
    @DisplayName("create: 同用户同证件 hash 重复拒绝")
    void createDuplicate() {
        when(attendeeMapper.selectOne(any())).thenReturn(Fixtures.attendee(1L, Fixtures.USER_A, "张三", "h", 0));
        assertThatThrownBy(() -> attendeeService.create(dto("张三", VALID_ID), Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1003);
    }

    @Test
    @DisplayName("create: 成功落库 hash/mask，is_self=0（实名本人由认证链路创建）")
    void createSuccess() {
        when(attendeeMapper.selectOne(any())).thenReturn(null);

        AttendeeVO vo = attendeeService.create(dto("张三", VALID_ID), Fixtures.USER_A);

        ArgumentCaptor<com.rechang.api.entity.Attendee> cap =
                ArgumentCaptor.forClass(com.rechang.api.entity.Attendee.class);
        verify(attendeeMapper).insert(cap.capture());
        assertThat(cap.getValue().getUserId()).isEqualTo(Fixtures.USER_A);
        assertThat(cap.getValue().getIdCardHash()).isEqualTo(HashUtils.sha256(VALID_ID));
        assertThat(cap.getValue().getIdCardMasked()).isEqualTo("1101**********0011");
        assertThat(cap.getValue().getIsSelf()).isZero();
        assertThat(vo.getName()).isEqualTo("张三");
    }

    @Test
    @DisplayName("update: 非本人观演人 NOT_FOUND")
    void updateNotOwned() {
        when(attendeeMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> attendeeService.update(9L, dto("李四", VALID_ID), Fixtures.USER_A))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("观演人不存在");
    }

    @Test
    @DisplayName("update: 换证件时格式与重复校验，排除自身")
    void updateWithNewIdCard() {
        var existing = Fixtures.attendee(5L, Fixtures.USER_A, "张三", "old-hash", 0);
        when(attendeeMapper.selectOne(any())).thenReturn(existing);

        // 新证件与他人重复 → 拒绝
        when(attendeeMapper.selectOne(any())).thenReturn(existing,
                Fixtures.attendee(6L, Fixtures.USER_A, "王五", "dup-hash", 0));
        assertThatThrownBy(() -> attendeeService.update(5L, dto("张三", VALID_ID), Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1003);

        // 唯一 → 更新成功
        when(attendeeMapper.selectOne(any())).thenReturn(existing, (com.rechang.api.entity.Attendee) null);
        AttendeeVO vo = attendeeService.update(5L, dto("张三三", VALID_ID), Fixtures.USER_A);
        verify(attendeeMapper).updateById(existing);
        assertThat(existing.getIdCardHash()).isEqualTo(HashUtils.sha256(VALID_ID));
        assertThat(existing.getAttendeeName()).isEqualTo("张三三");
        assertThat(vo.getName()).isEqualTo("张三三");
    }

    @Test
    @DisplayName("update: 新证件格式非法拒绝")
    void updateInvalidNewIdCard() {
        var existing = Fixtures.attendee(5L, Fixtures.USER_A, "张三", "old-hash", 0);
        when(attendeeMapper.selectOne(any())).thenReturn(existing);
        assertThatThrownBy(() -> attendeeService.update(5L, dto("张三", "999"), Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1004);
    }

    @Test
    @DisplayName("delete: 鉴权后删除")
    void deleteOwned() {
        var existing = Fixtures.attendee(5L, Fixtures.USER_A, "张三", "h", 0);
        when(attendeeMapper.selectOne(any())).thenReturn(existing);
        attendeeService.delete(5L, Fixtures.USER_A);
        verify(attendeeMapper).deleteById(5L);
    }

    @Test
    @DisplayName("list: 实体转 VO")
    void listMapping() {
        when(attendeeMapper.selectList(any())).thenReturn(List.of(
                Fixtures.attendee(1L, Fixtures.USER_A, "张三", "h1", 1)));
        List<AttendeeVO> list = attendeeService.list(Fixtures.USER_A);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getName()).isEqualTo("张三");
    }
}
