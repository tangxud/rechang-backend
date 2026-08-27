package com.rechang.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rechang.api.dto.AttendeeDTO;
import com.rechang.api.entity.Attendee;
import com.rechang.api.mapper.AttendeeMapper;
import com.rechang.api.vo.AttendeeVO;
import com.rechang.common.exception.BusinessException;
import com.rechang.common.result.ResultCode;
import com.rechang.common.utils.HashUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AttendeeService {

    private final AttendeeMapper attendeeMapper;

    public List<AttendeeVO> list(Long userId) {
        List<Attendee> attendees = attendeeMapper.selectList(
                new LambdaQueryWrapper<Attendee>().eq(Attendee::getUserId, userId));
        return attendees.stream().map(this::toVO).toList();
    }

    public AttendeeVO create(AttendeeDTO dto, Long userId) {
        if (!HashUtils.isValidIdCard(dto.getIdCardNo())) {
            throw new BusinessException(ResultCode.ID_CARD_FORMAT_ERROR);
        }
        String hash = HashUtils.sha256(dto.getIdCardNo());
        Attendee existing = attendeeMapper.selectOne(
                new LambdaQueryWrapper<Attendee>()
                        .eq(Attendee::getUserId, userId)
                        .eq(Attendee::getIdCardHash, hash));
        if (existing != null) {
            throw new BusinessException(ResultCode.ATTENDEE_DUPLICATE);
        }

        Attendee attendee = new Attendee();
        attendee.setUserId(userId);
        attendee.setAttendeeName(dto.getName());
        attendee.setIdCardHash(hash);
        attendee.setIdCardMasked(HashUtils.maskIdCard(dto.getIdCardNo()));
        attendee.setIsSelf(0);
        attendeeMapper.insert(attendee);

        return toVO(attendee);
    }

    public AttendeeVO update(Long id, AttendeeDTO dto, Long userId) {
        Attendee attendee = getOwnedAttendee(id, userId);

        if (dto.getIdCardNo() != null && !dto.getIdCardNo().isBlank()) {
            if (!HashUtils.isValidIdCard(dto.getIdCardNo())) {
                throw new BusinessException(ResultCode.ID_CARD_FORMAT_ERROR);
            }
            String hash = HashUtils.sha256(dto.getIdCardNo());
            Attendee existing = attendeeMapper.selectOne(
                    new LambdaQueryWrapper<Attendee>()
                            .eq(Attendee::getUserId, userId)
                            .eq(Attendee::getIdCardHash, hash)
                            .ne(Attendee::getId, id));
            if (existing != null) {
                throw new BusinessException(ResultCode.ATTENDEE_DUPLICATE);
            }
            attendee.setIdCardHash(hash);
            attendee.setIdCardMasked(HashUtils.maskIdCard(dto.getIdCardNo()));
        }

        attendee.setAttendeeName(dto.getName());
        attendeeMapper.updateById(attendee);

        return toVO(attendee);
    }

    public void delete(Long id, Long userId) {
        Attendee attendee = getOwnedAttendee(id, userId);
        attendeeMapper.deleteById(attendee.getId());
    }

    private Attendee getOwnedAttendee(Long id, Long userId) {
        Attendee attendee = attendeeMapper.selectOne(
                new LambdaQueryWrapper<Attendee>()
                        .eq(Attendee::getId, id)
                        .eq(Attendee::getUserId, userId));
        if (attendee == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "观演人不存在");
        }
        return attendee;
    }

    private AttendeeVO toVO(Attendee attendee) {
        AttendeeVO vo = new AttendeeVO();
        vo.setId(attendee.getId());
        vo.setName(attendee.getAttendeeName());
        vo.setIdCardMasked(attendee.getIdCardMasked());
        vo.setCreatedAt(attendee.getCreateTime());
        return vo;
    }
}
