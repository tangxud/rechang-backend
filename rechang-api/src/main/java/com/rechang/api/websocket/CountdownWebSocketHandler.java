package com.rechang.api.websocket;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rechang.api.entity.Performance;
import com.rechang.api.mapper.PerformanceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class CountdownWebSocketHandler extends TextWebSocketHandler {

    private final PerformanceMapper performanceMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    private java.util.concurrent.ScheduledExecutorService scheduler;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String path = session.getUri().getPath();
        Long performanceId = extractPerformanceId(path);
        if (performanceId == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        Performance perf = performanceMapper.selectById(performanceId);
        if (perf == null) {
            session.sendMessage(new TextMessage("{\"type\":\"error\",\"message\":\"performance not found\"}"));
            session.close(CloseStatus.NORMAL);
            return;
        }

        sendTimeSync(session, perf);

        scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (session.isOpen()) {
                    sendTimeSync(session, perf);
                }
            } catch (Exception e) {
                log.warn("Failed to send time_sync: {}", e.getMessage());
            }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);

        tasks.put(session.getId(), future);
        log.info("WebSocket countdown connected: performanceId={}, sessionId={}", performanceId, session.getId());
    }

    private void sendTimeSync(WebSocketSession session, Performance perf) throws IOException {
        long serverTime = System.currentTimeMillis();
        long countdownSeconds = 0;
        if (perf.getSaleStartTime() != null) {
            countdownSeconds = (perf.getSaleStartTime().getTime() - serverTime) / 1000;
            if (countdownSeconds < 0) countdownSeconds = 0;
        }

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", countdownSeconds <= 0 ? "sale_started" : "time_sync");
        msg.put("performanceId", perf.getId());
        msg.put("serverTime", serverTime);
        msg.put("saleStartTime", perf.getSaleStartTime() != null ? perf.getSaleStartTime().getTime() : 0);
        msg.put("countdownSeconds", countdownSeconds);

        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (message.getPayload().contains("ping")) {
            Map<String, Object> pong = new HashMap<>();
            pong.put("type", "pong");
            pong.put("timestamp", System.currentTimeMillis());
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(pong)));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ScheduledFuture<?> future = tasks.remove(session.getId());
        if (future != null) future.cancel(false);
        if (scheduler != null) scheduler.shutdown();
        log.info("WebSocket countdown disconnected: sessionId={}, status={}", session.getId(), status);
    }

    private Long extractPerformanceId(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("countdown".equals(parts[i]) && i + 1 < parts.length) {
                try { return Long.parseLong(parts[i + 1]); } catch (NumberFormatException e) { return null; }
            }
        }
        return null;
    }
}
