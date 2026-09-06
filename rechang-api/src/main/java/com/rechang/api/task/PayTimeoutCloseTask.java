package com.rechang.api.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rechang.api.entity.OrderEntity;
import com.rechang.api.entity.Ticket;
import com.rechang.api.mapper.OrderMapper;
import com.rechang.api.mapper.TicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 支付超时自动关单（票 #30005 验收 3）：PENDING_PAY 超过 15 分钟（与下单座位锁 TTL 一致）关闭。
 * 乐观锁保证与支付回调/用户取消并发时仅一方生效——关单失败（0 行）说明已被支付或取消，静默跳过。
 * 说明：设计文档规划 XXL-JOB 承载定时任务，MVP 阶段以 Spring @Scheduled 过渡，接入 XXL-JOB 后迁移。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayTimeoutCloseTask {

    /** 与 OrderService 座位锁 LOCK_TTL_MINUTES 保持一致：锁过期即可安全关单释放库存 */
    static final long PAY_TIMEOUT_MINUTES = 15;

    private final OrderMapper orderMapper;
    private final TicketMapper ticketMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    /** 自注入代理：closeOne 的 @Transactional 需经代理调用才生效（规避自调用绕过 AOP） */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private PayTimeoutCloseTask self;

    @Scheduled(fixedDelay = 60_000)
    public void closeExpiredOrders() {
        Date deadline = new Date(System.currentTimeMillis() - PAY_TIMEOUT_MINUTES * 60 * 1000);
        List<OrderEntity> expired = orderMapper.selectList(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getStatus, "PENDING_PAY")
                .lt(OrderEntity::getCreateTime, deadline));
        for (OrderEntity order : expired) {
            try {
                self.closeOne(order);
            } catch (Exception e) {
                // 单笔失败不阻断本批（事务只包住单笔；批间相互独立）
                log.warn("超时关单失败: orderNo={}, err={}", order.getOrderNo(), e.getMessage());
            }
        }
        if (!expired.isEmpty()) {
            log.info("支付超时关单本轮处理 {} 笔", expired.size());
        }
    }

    @Transactional
    public void closeOne(OrderEntity order) {
        order.setStatus("CANCELLED");
        order.setCancelledAt(new Date());
        order.setCancelReason("TIMEOUT");
        order.setUpdateTime(new Date());
        if (orderMapper.updateById(order) == 0) {
            return; // 并发支付回调/用户取消已生效，跳过
        }

        List<Ticket> tickets = ticketMapper.selectList(
                new LambdaQueryWrapper<Ticket>().eq(Ticket::getOrderId, order.getId()));
        for (Ticket t : tickets) {
            if (t.getSeatId() != null) {
                redisTemplate.delete("seat:lock:" + order.getPerformanceId() + ":" + t.getSeatId());
            }
        }
        ticketMapper.delete(new LambdaQueryWrapper<Ticket>().eq(Ticket::getOrderId, order.getId()));
        log.info("订单支付超时关闭: orderNo={}", order.getOrderNo());
    }
}
