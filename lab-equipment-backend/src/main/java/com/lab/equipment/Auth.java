package com.lab.equipment;

import static com.lab.equipment.Db.*;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Service;
import org.springframework.web.filter.OncePerRequestFilter;

record User(String id, String name, List<String> roles) {
  boolean is(String r) {
    return roles.contains(r);
  }

  boolean admin() {
    return is("ADMIN");
  }

  boolean staff() {
    return is("LAB_STAFF") || admin();
  }

  boolean tech() {
    return is("TECHNICIAN");
  }
}

@Service
public class Auth {

  final Db db;
  final StringRedisTemplate redis;
  final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
  final String prefix;

  Auth(
    Db db,
    StringRedisTemplate redis,
    org.springframework.core.env.Environment env
  ) {
    this.db = db;
    this.redis = redis;
    String url = env.getProperty("spring.datasource.url", "").split("\\?")[0];
    String name = url
      .substring(url.lastIndexOf('/') + 1)
      .replaceAll("[^A-Za-z0-9_]", "_");
    this.prefix = "lab-equipment:" + name + ":";
  }

  User current() {
    Object p = SecurityContextHolder.getContext()
      .getAuthentication()
      .getPrincipal();
    if (!(p instanceof User u)) throw new ApiError(
      401,
      "UNAUTHORIZED",
      "请先登录"
    );
    return u;
  }

  User user(String id) {
    var m = db.one("SELECT * FROM sys_user WHERE id=? AND enabled=TRUE", id);
    var roles = db
      .rows("SELECT role_code FROM sys_user_role WHERE user_id=?", id)
      .stream()
      .map(r -> str(r, "roleCode"))
      .toList();
    return new User(id, str(m, "name"), roles);
  }

  Map<String, Object> profile(User u) {
    var m = db.one(
      "SELECT id,username,name,role,enabled,created_at FROM sys_user WHERE id=?",
      u.id()
    );
    m.put("roles", u.roles());
    m.put(
      "labIds",
      db
        .rows("SELECT lab_id FROM lab_staff_scope WHERE user_id=?", u.id())
        .stream()
        .map(r -> r.get("labId"))
        .toList()
    );
    return m;
  }

  Map<String, Object> login(Map<String, Object> body, String ip) {
    String username = required(body, "username", 80),
      password = required(body, "password", 100);
    String key = prefix + "login:" + hash(ip + username);
    Long n = redis.opsForValue().increment(key);
    if (n != null && n == 1) redis.expire(key, Duration.ofMinutes(10));
    if (n != null && n > 30) throw new ApiError(
      429,
      "RATE_LIMITED",
      "登录尝试过多，请 10 分钟后再试"
    );
    var rows = db.rows("SELECT * FROM sys_user WHERE username=?", username);
    if (
      rows.isEmpty() ||
      !bool(rows.getFirst().get("enabled")) ||
      !passwords.matches(password, str(rows.getFirst(), "passwordHash"))
    ) throw new ApiError(401, "INVALID_LOGIN", "账号或密码错误，或账户已停用");
    var row = rows.getFirst();
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String token = HexFormat.of().formatHex(bytes);
    redis
      .opsForValue()
      .set(
        prefix + "session:" + hash(token),
        str(row, "id") + ":" + row.get("authVersion"),
        Duration.ofDays(7)
      );
    redis.delete(key);
    db.audit(str(row, "id"), "LOGIN", str(row, "id"), "登录成功");
    return Map.of("token", token, "user", profile(user(str(row, "id"))));
  }

  User authenticate(String token) {
    String s = redis.opsForValue().get(prefix + "session:" + hash(token));
    if (s == null) throw new ApiError(
      401,
      "SESSION_EXPIRED",
      "登录已过期，请重新登录"
    );
    String[] v = s.split(":");
    var row = db.one("SELECT * FROM sys_user WHERE id=?", v[0]);
    if (
      !bool(row.get("enabled")) ||
      !String.valueOf(row.get("authVersion")).equals(v[1])
    ) throw new ApiError(401, "SESSION_EXPIRED", "账户权限已更新，请重新登录");
    return user(v[0]);
  }

  void logout(String token) {
    redis.delete(prefix + "session:" + hash(token));
  }

  void admin(User u) {
    if (!u.admin()) throw new ApiError(403, "FORBIDDEN", "需要管理员权限");
  }

  void lab(User u, String lab) {
    if (u.admin()) return;
    if (
      !u.is("LAB_STAFF") ||
      db.count(
        "SELECT COUNT(*) FROM lab_staff_scope WHERE user_id=? AND lab_id=?",
        u.id(),
        lab
      ) == 0
    ) throw new ApiError(403, "FORBIDDEN", "没有该实验室的管理权限");
  }
}

@Configuration
class SecurityConfig {

  @Bean
  SecurityFilterChain chain(HttpSecurity http, Auth auth, Db db)
    throws Exception {
    var filter = new OncePerRequestFilter() {
      protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
      ) throws ServletException, IOException {
        String path = request.getRequestURI();
        String header = request.getHeader("Authorization");
        if (
          !path.startsWith("/api/") ||
          path.equals("/api/health") ||
          path.equals("/api/auth/login")
        ) {
          chain.doFilter(request, response);
          return;
        }
        try {
          if (
            header == null || !header.startsWith("Bearer ")
          ) throw new ApiError(401, "UNAUTHORIZED", "请先登录");
          User u = auth.authenticate(header.substring(7));
          SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
              u,
              null,
              u
                .roles()
                .stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList()
            )
          );
        } catch (Exception e) {
          int status = e instanceof ApiError a ? a.status : 503;
          response.setStatus(status);
          response.setContentType("application/json;charset=UTF-8");
          response
            .getWriter()
            .write(
              db.encode(
                Map.of(
                  "code",
                  status == 503 ? "AUTH_UNAVAILABLE" : "UNAUTHORIZED",
                  "message",
                  status == 503 ? "认证服务暂不可用" : e.getMessage()
                )
              )
            );
          return;
        }
        chain.doFilter(request, response);
      }
    };
    return http
      .csrf(c -> c.disable())
      .cors(c -> c.disable())
      .sessionManagement(c ->
        c.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
      )
      .authorizeHttpRequests(c ->
        c
          .requestMatchers(
            "/api/health",
            "/api/auth/login",
            "/internal/**",
            "/error"
          )
          .permitAll()
          .anyRequest()
          .authenticated()
      )
      .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
      .build();
  }
}
