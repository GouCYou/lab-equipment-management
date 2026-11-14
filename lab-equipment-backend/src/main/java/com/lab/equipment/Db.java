package com.lab.equipment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Db {

  final JdbcTemplate jdbc;
  final ObjectMapper json;

  public Db(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public static Timestamp now() {
    return Timestamp.from(Instant.now());
  }

  public static String hash(String s) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
          s.getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String encode(Object o) {
    try {
      return json
        .writer()
        .with(
          com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS
        )
        .writeValueAsString(o);
    } catch (Exception e) {
      throw new IllegalArgumentException("数据格式错误");
    }
  }

  public Map<String, Object> object(String s) {
    try {
      return json.readValue(s, new TypeReference<>() {});
    } catch (Exception e) {
      return new LinkedHashMap<>();
    }
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(
      sql,
      (rs, n) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        var meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
          String key = camel(meta.getColumnLabel(i));
          Object v = rs.getObject(i);
          if (v instanceof Timestamp t) v = t.toInstant().toString();
          if (v instanceof java.time.LocalDateTime t) v = t
            .toInstant(java.time.ZoneOffset.UTC)
            .toString();
          if (v instanceof java.time.LocalDate d) v = d.toString();
          if (v instanceof java.sql.Date d) v = d.toString();
          if (v instanceof byte[] bytes) v = new String(
            bytes,
            StandardCharsets.UTF_8
          );
          if (
            v instanceof String s &&
            Set.of(
              "photoIds",
              "returnPhotoIds",
              "attachments",
              "cards",
              "citations",
              "contextJson",
              "resultJson",
              "rolesJson"
            ).contains(key)
          ) try {
            v = json.readValue(s, Object.class);
          } catch (Exception ignored) {}
          m.put(key, v);
        }
        return m;
      },
      args
    );
  }

  public Map<String, Object> one(String sql, Object... args) {
    var l = rows(sql, args);
    if (l.isEmpty()) throw new ApiError(
      404,
      "NOT_FOUND",
      "记录不存在或不可访问"
    );
    return l.getFirst();
  }

  public long count(String sql, Object... args) {
    Long n = jdbc.queryForObject(sql, Long.class, args);
    return n == null ? 0 : n;
  }

  public int update(String sql, Object... args) {
    return jdbc.update(sql, args);
  }

  public void event(
    String type,
    String target,
    String action,
    String actor,
    String note
  ) {
    update(
      "INSERT INTO business_event VALUES(?,?,?,?,?,?,?)",
      id(),
      type,
      target,
      action,
      actor,
      note,
      now()
    );
  }

  public void audit(String actor, String action, String target, String note) {
    update(
      "INSERT INTO audit_log VALUES(?,?,?,?,?,?)",
      id(),
      actor,
      action,
      target,
      note,
      now()
    );
  }

  public List<Map<String, Object>> events(String type, String target) {
    return rows(
      "SELECT b.*,u.name AS actor_name FROM business_event b LEFT JOIN sys_user u ON u.id=b.actor_id WHERE business_type=? AND business_id=? ORDER BY b.created_at,b.id",
      type,
      target
    );
  }

  static String camel(String s) {
    StringBuilder b = new StringBuilder();
    boolean cap = false;
    for (char c : s.toCharArray()) {
      if (c == '_') {
        cap = true;
        continue;
      }
      b.append(cap ? Character.toUpperCase(c) : c);
      cap = false;
    }
    return b.toString();
  }

  static String str(Map<String, ?> m, String k) {
    Object v = m.get(k);
    return v == null ? "" : String.valueOf(v);
  }

  static String required(Map<String, ?> m, String k, int max) {
    String s = str(m, k).trim();
    if (s.isEmpty() || s.length() > max) throw new ApiError(
      422,
      "INVALID_FIELD",
      k + " 不能为空且长度不得超过 " + max
    );
    return s;
  }

  static boolean bool(Object o) {
    return (
      Boolean.TRUE.equals(o) ||
      "true".equals(String.valueOf(o)) ||
      "1".equals(String.valueOf(o))
    );
  }

  static List<String> strings(Object o) {
    if (o instanceof List<?> l) return l.stream().map(String::valueOf).toList();
    return List.of();
  }
}
