package com.autoblog;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.util.Set;

/** Local single-user app: reject cross-site browser writes without exposing a public API. */
@Component
public class LocalOriginFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String origin=request.getHeader("Origin");
        if(!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())&&origin!=null) {
            boolean allowed=false;
            try {
                URI uri=URI.create(origin);
                allowed=Set.of("localhost","127.0.0.1").contains(uri.getHost())&&"http".equals(uri.getScheme())&&(uri.getPort()==5173||uri.getPort()==request.getServerPort());
            } catch(Exception ignored) {}
            if(!allowed) {response.setStatus(403);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"message\":\"허용되지 않은 요청 출처입니다.\"}");return;}
        }
        chain.doFilter(request,response);
    }
}
