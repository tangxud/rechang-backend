package com.rechang.api.support;

import com.rechang.api.entity.Attendee;
import com.rechang.api.entity.OrderEntity;
import com.rechang.api.entity.Performance;
import com.rechang.api.entity.PerformancePriceZone;
import com.rechang.api.entity.Seat;
import com.rechang.api.entity.Ticket;

import java.util.Date;

/**
 * 测试实体工厂：集中默认值，单测内按需覆盖，避免每个用例手工拼十几个字段。
 */
public final class Fixtures {

    public static final long USER_A = 100L;   // 原购买者
    public static final long USER_B = 200L;   // 受赠者
    public static final long PERF_ID = 1L;
    public static final long ORDER_ID = 10L;
    public static final long TICKET_ID = 1000L;

    private Fixtures() {
    }

    public static Performance performance(String publishStatus) {
        Performance p = new Performance();
        p.setId(PERF_ID);
        p.setPerfName("周杰伦嘉年华");
        p.setPublishStatus(publishStatus);
        p.setPurchaseLimitPerId(4);
        p.setMinPrice(18000);
        p.setTourId("TOUR_JAY_2026");
        p.setCityCode("BEIJING");
        return p;
    }

    public static PerformancePriceZone zone(String region, Integer price) {
        PerformancePriceZone z = new PerformancePriceZone();
        z.setId((long) region.hashCode());
        z.setPerformanceId(PERF_ID);
        z.setRegion(region);
        z.setZoneName(region + "区");
        z.setPrice(price);
        z.setTotalCount(50);
        return z;
    }

    public static Seat seat(long id, String region, String row, String col, String status) {
        Seat s = new Seat();
        s.setId(id);
        s.setVenueId(3L);
        s.setRegion(region);
        s.setRowLabel(row);
        s.setColLabel(col);
        s.setSeatLabel(row + "排" + col + "座");
        s.setStatus(status);
        return s;
    }

    public static OrderEntity order(long id, long userId, long performanceId, String status) {
        OrderEntity o = new OrderEntity();
        o.setId(id);
        o.setOrderNo("RC202601011200000001");
        o.setUserId(userId);
        o.setPerformanceId(performanceId);
        o.setTotalAmount(38000);
        o.setRefundedAmount(0);
        o.setSource("PURCHASE");
        o.setStatus(status);
        o.setVersion(0);
        o.setPayChannel("WECHAT");
        o.setCreateTime(new Date());
        return o;
    }

    public static Ticket ticket(long id, long orderId, long performanceId, String status) {
        Ticket t = new Ticket();
        t.setId(id);
        t.setOrderId(orderId);
        t.setPerformanceId(performanceId);
        t.setFaceAmount(38000);
        t.setOwnerUserId(USER_A);
        t.setOriginalUserId(USER_A);
        t.setStatus(status);
        t.setTransferCount(0);
        t.setFaceVerified(0);
        t.setAttendeeIdCardHash("");
        return t;
    }

    public static Attendee attendee(long id, long userId, String name, String idCardHash, int isSelf) {
        Attendee a = new Attendee();
        a.setId(id);
        a.setUserId(userId);
        a.setAttendeeName(name);
        a.setIdCardHash(idCardHash);
        a.setIdCardMasked("3301********0012");
        a.setIsSelf(isSelf);
        return a;
    }

    /* ---- 时间便捷构造 ---- */

    public static Date msFromNow(long ms) {
        return new Date(System.currentTimeMillis() + ms);
    }

    public static Date daysFromNow(double days) {
        return msFromNow((long) (days * 24 * 60 * 60 * 1000L));
    }

    public static Date hoursAgo(double hours) {
        return new Date(System.currentTimeMillis() - (long) (hours * 3600 * 1000));
    }

    public static Date daysAgo(double days) {
        return hoursAgo(days * 24);
    }
}
