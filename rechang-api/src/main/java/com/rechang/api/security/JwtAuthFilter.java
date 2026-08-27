package com.rechang.api.security;

import com.rechang.common.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (StringUtils.hasText(token)) {
            try {
                Claims claims = JwtUtils.parseToken(token);
                Long userId = JwtUtils.getUserId(token);
                String openid = claims.get("openid", String.class);

                UserContext.set(new UserContext.UserInfo(userId, openid));

                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    userId, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                log.debug("JWT parse failed: {}", e.getMessage());
                UserContext.clear();
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        // GET /api/shows/** 公开（演出列表/详情/评价列表/想看人数）
        boolean publicShowGet = "GET".equals(method) && path.startsWith("/api/shows")
                && !path.contains("/subscribe")
                && !path.contains("/want");
        return path.startsWith("/actuator") ||
               path.startsWith("/api/auth/login") ||
               path.startsWith("/api/home") ||
               path.startsWith("/api/search") ||
               publicShowGet ||
               path.startsWith("/ws/");
    }
}
