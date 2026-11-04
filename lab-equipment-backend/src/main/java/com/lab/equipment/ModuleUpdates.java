package com.lab.equipment;

import static com.lab.equipment.ApiError.require;
import static com.lab.equipment.Db.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModuleUpdates {

  final Db db;
  final Queries q;
  final Knowledge knowledge;

  ModuleUpdates(Db db, Queries q, Knowledge knowledge) {
    this.db = db;
    this.q = q;
    this.knowledge = knowledge;
  }

  String fingerprint(Object data) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
          db.encode(data).getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  String snapshot(List<Map<String, Object>> rows, String type) {
    Set<String> ids = new HashSet<>();
    List<String> values = new ArrayList<>();
    for (var row : rows) {
      ids.add(str(row, "id"));
      Map<String, Object> stable = new TreeMap<>();
      for (String field : List.of(
        "id",
        "name",
        "title",
        "status",
        "enabled",
        "operationalStatus",
        "borrowed",
        "available",
        "userId",
        "assigneeId",
        "updatedAt",
        "returnedAt",
        "publishedVersionId",
        "latestStatus",
        "indexStatus",
        "versionNo",
        "chunkCount",
        "createdAt",
        "readAt",
        "assetNo",
        "model",
        "location",
        "labId",
        "categoryId",
        "description"
      ))
        if (row.containsKey(field) && !field.equals("readAt")) stable.put(
          field,
          row.get(field)
        );
      values.add(db.encode(stable));
    }
    if (!type.isBlank()) for (var event : db.rows(
      "SELECT business_id,MAX(created_at) AS latest,COUNT(*) AS total FROM business_event WHERE business_type=? GROUP BY business_id",
      type
    ))
      if (ids.contains(str(event, "businessId"))) values.add(
        db.encode(new TreeMap<>(event))
      );
    if (type.equals("KNOWLEDGE")) for (var version : db.rows(
      "SELECT document_id,MAX(created_at) AS latest,COUNT(*) AS total FROM knowledge_version GROUP BY document_id"
    ))
      if (ids.contains(str(version, "documentId"))) values.add(
        db.encode(new TreeMap<>(version))
      );
    Collections.sort(values);
    return fingerprint(values);
  }

  Map<String, String> current(User u) {
    Map<String, String> out = new LinkedHashMap<>();
    out.put(
      "equipment",
      snapshot(q.equipment(u, "", "", "", false), "EQUIPMENT")
    );
    out.put("reservations", snapshot(q.reservations(u), "RESERVATION"));
    out.put("loans", snapshot(q.loans(u, false), "LOAN"));
    out.put("tickets", snapshot(q.tickets(u), "TICKET"));
    out.put("knowledge", snapshot(knowledge.list(u), "KNOWLEDGE"));
    out.put("notifications", snapshot(q.notifications(u), ""));
    out.put(
      "chat",
      snapshot(
        db.rows(
          "SELECT id,title,updated_at FROM conversation WHERE user_id=? ORDER BY id",
          u.id()
        ),
        ""
      )
    );
    String business = fingerprint(
      List.of(
        out.get("equipment"),
        out.get("reservations"),
        out.get("loans"),
        out.get("tickets")
      )
    );
    out.put("dashboard", business);
    if (u.staff()) out.put("statistics", business);
    if (u.admin()) out.put(
      "admin",
      fingerprint(
        List.of(
          db.rows(
            "SELECT id,name,role,enabled,auth_version FROM sys_user ORDER BY id"
          ),
          db.rows("SELECT * FROM sys_user_role ORDER BY user_id,role_code"),
          q.labs(),
          q.categories(),
          db.rows(
            "SELECT action,COUNT(*) AS total,MAX(created_at) AS latest FROM audit_log WHERE action IN ('SAVE_USER','SAVE_LAB','SAVE_AI_SETTINGS') GROUP BY action ORDER BY action"
          )
        )
      )
    );
    return out;
  }

  @Transactional
  public Map<String, Object> list(User u) {
    Map<String, Object> out = new LinkedHashMap<>();
    current(u).forEach((key, value) -> {
      db.update(
        "INSERT IGNORE INTO module_seen VALUES(?,?,?,?)",
        u.id(),
        key,
        value,
        now()
      );
      var seen = db.one(
        "SELECT fingerprint FROM module_seen WHERE user_id=? AND module_key=?",
        u.id(),
        key
      );
      out.put(
        key,
        Map.of(
          "fingerprint",
          value,
          "unread",
          !value.equals(str(seen, "fingerprint"))
        )
      );
    });
    return out;
  }

  @Transactional
  public Map<String, Object> read(
    User u,
    String module,
    Map<String, Object> body
  ) {
    require(current(u).containsKey(module), "没有该模块权限");
    String value = str(body, "fingerprint");
    require(value.matches("[a-f0-9]{64}"), "模块状态无效");
    db.update(
      "INSERT INTO module_seen VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE fingerprint=VALUES(fingerprint),seen_at=VALUES(seen_at)",
      u.id(),
      module,
      value,
      now()
    );
    return Map.of("ok", true);
  }
}
