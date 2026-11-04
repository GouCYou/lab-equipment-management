package com.lab.equipment;

import static com.lab.equipment.Db.*;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Queries {

  final Db db;
  final Auth auth;

  Queries(Db db, Auth auth) {
    this.db = db;
    this.auth = auth;
  }

  record Scope(String sql, List<Object> args) {}

  Scope scope(User u, String alias, String owner, String assigned) {
    if (u.admin()) return new Scope("1=1", List.of());
    List<String> clauses = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    if (u.is("LAB_STAFF")) {
      clauses.add(
        alias +
          ".lab_id IN (SELECT lab_id FROM lab_staff_scope WHERE user_id=?)"
      );
      args.add(u.id());
    }
    if (assigned != null && u.tech()) {
      clauses.add(alias + "." + assigned + "=?");
      args.add(u.id());
    }
    if (owner != null) {
      clauses.add(alias + "." + owner + "=?");
      args.add(u.id());
    }
    return new Scope(
      clauses.isEmpty() ? "1=0" : "(" + String.join(" OR ", clauses) + ")",
      args
    );
  }

  static final String EQUIPMENT =
    "SELECT e.*,l.name AS lab_name,l.location,c.name AS category_name,CASE WHEN e.enabled=FALSE THEN 'DISABLED' WHEN EXISTS(SELECT 1 FROM equipment_block b WHERE b.equipment_id=e.id AND b.ends_at IS NULL) THEN 'MAINTENANCE' ELSE 'NORMAL' END AS operational_status,EXISTS(SELECT 1 FROM loan_record x WHERE x.equipment_id=e.id AND x.returned_at IS NULL) AS borrowed FROM equipment e JOIN laboratory l ON l.id=e.lab_id JOIN equipment_category c ON c.id=e.category_id ";

  List<Map<String, Object>> equipment(
    User u,
    String search,
    String lab,
    String category,
    boolean onlyAvailable
  ) {
    var rows = db.rows(
      EQUIPMENT +
        " WHERE l.enabled=TRUE AND (?='' OR e.name LIKE ? OR e.asset_no LIKE ? OR e.model LIKE ?) AND (?='' OR e.lab_id=?) AND (?='' OR e.category_id=?) ORDER BY e.asset_no",
      search,
      "%" + search + "%",
      "%" + search + "%",
      "%" + search + "%",
      lab,
      lab,
      category,
      category
    );
    for (var e : rows)
      e.put(
        "available",
        str(e, "operationalStatus").equals("NORMAL") &&
          !bool(e.get("borrowed")) &&
          db.count(
            "SELECT COUNT(*) FROM reservation WHERE equipment_id=? AND status='APPROVED' AND starts_at<=? AND ends_at>?",
            e.get("id"),
            now(),
            now()
          ) == 0
      );
    return onlyAvailable
      ? rows
          .stream()
          .filter(e -> bool(e.get("available")))
          .toList()
      : rows;
  }

  Map<String, Object> equipment(User u, String id) {
    var e = db.one(EQUIPMENT + " WHERE e.id=?", id);
    e.put(
      "blocks",
      db.rows(
        "SELECT id,source_type,reason,starts_at,ends_at FROM equipment_block WHERE equipment_id=? AND ends_at IS NULL",
        id
      )
    );
    e.put(
      "reservations",
      db.rows(
        "SELECT starts_at,ends_at,status FROM reservation WHERE equipment_id=? AND status='APPROVED' AND ends_at>? ORDER BY starts_at",
        id,
        now()
      )
    );
    e.put("events", db.events("EQUIPMENT", id));
    return e;
  }

  List<Map<String, Object>> reservations(User u) {
    var s = scope(u, "r", "user_id", null);
    return db.rows(
      "SELECT r.*,e.name AS equipment_name,e.asset_no,e.model,u.name AS user_name,l.name AS lab_name FROM reservation r JOIN equipment e ON e.id=r.equipment_id JOIN sys_user u ON u.id=r.user_id JOIN laboratory l ON l.id=r.lab_id WHERE " +
        s.sql() +
        " ORDER BY r.created_at DESC",
      s.args().toArray()
    );
  }

  Map<String, Object> reservation(User u, String id) {
    return reservations(u)
      .stream()
      .filter(r -> str(r, "id").equals(id))
      .findFirst()
      .orElseThrow(() ->
        new ApiError(404, "NOT_FOUND", "预约不存在或不可访问")
      );
  }

  List<Map<String, Object>> loans(User u, boolean active) {
    var s = scope(u, "r", "user_id", null);
    return db.rows(
      "SELECT r.*,e.name AS equipment_name,e.asset_no,e.model,u.name AS user_name,l.name AS lab_name,(r.returned_at IS NULL AND r.due_at<UTC_TIMESTAMP(3)) AS overdue FROM loan_record r JOIN equipment e ON e.id=r.equipment_id JOIN sys_user u ON u.id=r.user_id JOIN laboratory l ON l.id=r.lab_id WHERE " +
        s.sql() +
        (active ? " AND r.returned_at IS NULL" : "") +
        " ORDER BY r.issued_at DESC",
      s.args().toArray()
    );
  }

  Map<String, Object> loan(User u, String id) {
    return loans(u, false)
      .stream()
      .filter(r -> str(r, "id").equals(id))
      .findFirst()
      .orElseThrow(() ->
        new ApiError(404, "NOT_FOUND", "借用不存在或不可访问")
      );
  }

  List<Map<String, Object>> tickets(User u) {
    var s = scope(u, "t", "reporter_id", "assignee_id");
    return db.rows(
      "SELECT t.*,e.name AS equipment_name,e.asset_no,e.model,l.name AS lab_name,u.name AS reporter_name,a.name AS assignee_name FROM fault_ticket t JOIN equipment e ON e.id=t.equipment_id JOIN laboratory l ON l.id=t.lab_id JOIN sys_user u ON u.id=t.reporter_id LEFT JOIN sys_user a ON a.id=t.assignee_id WHERE " +
        s.sql() +
        " ORDER BY t.created_at DESC",
      s.args().toArray()
    );
  }

  Map<String, Object> ticket(User u, String id) {
    var t = tickets(u)
      .stream()
      .filter(r -> str(r, "id").equals(id))
      .findFirst()
      .orElseThrow(() ->
        new ApiError(404, "NOT_FOUND", "工单不存在或不可访问")
      );
    t.put("events", db.events("TICKET", id));
    return t;
  }

  List<Map<String, Object>> labs() {
    return db.rows("SELECT * FROM laboratory ORDER BY name");
  }

  List<Map<String, Object>> categories() {
    return db.rows("SELECT * FROM equipment_category ORDER BY name");
  }

  List<Map<String, Object>> technicians(User u) {
    if (!u.staff()) throw new ApiError(403, "FORBIDDEN", "需要管理权限");
    return db.rows(
      "SELECT DISTINCT u.id,u.name FROM sys_user u JOIN sys_user_role r ON u.id=r.user_id WHERE r.role_code='TECHNICIAN' AND u.enabled=TRUE ORDER BY u.name"
    );
  }

  List<Map<String, Object>> notifications(User u) {
    return db.rows(
      "SELECT * FROM notification WHERE user_id=? ORDER BY created_at DESC LIMIT 150",
      u.id()
    );
  }

  Map<String, Object> dashboard(User u) {
    var eq = equipment(u, "", "", "", false);
    var loans = loans(u, false);
    var reservations = reservations(u);
    var tickets = tickets(u);
    return Map.of(
      "equipmentCount",
      eq.size(),
      "availableCount",
      eq
        .stream()
        .filter(e -> bool(e.get("available")))
        .count(),
      "activeLoans",
      loans
        .stream()
        .filter(e -> e.get("returnedAt") == null)
        .count(),
      "overdueCount",
      loans
        .stream()
        .filter(e -> bool(e.get("overdue")))
        .count(),
      "pendingReservations",
      reservations
        .stream()
        .filter(e -> str(e, "status").equals("PENDING"))
        .count(),
      "openTickets",
      tickets
        .stream()
        .filter(
          e ->
            !Set.of("CLOSED", "REJECTED", "CANCELLED").contains(
              str(e, "status")
            )
        )
        .count(),
      "recentTickets",
      tickets.stream().limit(5).toList(),
      "recentLoans",
      loans.stream().limit(5).toList()
    );
  }

  Map<String, Object> statistics(User u, int days, String lab) {
    if (!u.staff()) throw new ApiError(
      403,
      "FORBIDDEN",
      "统计分析需要实验室管理权限"
    );
    if (!lab.isEmpty()) auth.lab(u, lab);
    days = Math.max(1, Math.min(days, 366));
    Instant end = Instant.now();
    Instant start = end.minus(Duration.ofDays(days));
    var eq = equipment(u, "", lab, "", false)
      .stream()
      .filter(
        e ->
          u.admin() ||
          db.count(
            "SELECT COUNT(*) FROM lab_staff_scope WHERE user_id=? AND lab_id=?",
            u.id(),
            e.get("labId")
          ) > 0
      )
      .toList();
    double totalOpen = 0,
      totalUsed = 0;
    List<Map<String, Object>> rates = new ArrayList<>();
    for (var e : eq) {
      String id = str(e, "id");
      var l = db.one("SELECT * FROM laboratory WHERE id=?", e.get("labId"));
      List<long[]> eligible = new ArrayList<>();
      ZoneId zone = ZoneId.of("Asia/Shanghai");
      LocalDate first = start.atZone(zone).toLocalDate(),
        last = end.atZone(zone).toLocalDate();
      var rules = db.rows(
        "SELECT * FROM lab_open_rule WHERE lab_id=? AND effective_from<? AND (effective_to IS NULL OR effective_to>?)",
        e.get("labId"),
        java.sql.Timestamp.from(end),
        java.sql.Timestamp.from(start)
      );
      long born = Instant.parse(str(e, "createdAt")).toEpochMilli();
      for (
        LocalDate date = first;
        !date.isAfter(last);
        date = date.plusDays(1)
      ) {
        var exceptions = db.rows(
          "SELECT is_open FROM lab_calendar WHERE lab_id=? AND local_date=?",
          e.get("labId"),
          date
        );
        if (
          !exceptions.isEmpty() && !bool(exceptions.getFirst().get("isOpen"))
        ) continue;
        for (var rule : rules) {
          long from = Instant.parse(str(rule, "effectiveFrom")).toEpochMilli(),
            to =
              rule.get("effectiveTo") == null
                ? end.toEpochMilli()
                : Instant.parse(str(rule, "effectiveTo")).toEpochMilli();
          long a = Math.max(
            Math.max(start.toEpochMilli(), born),
            Math.max(
              from,
              date
                .atStartOfDay(zone)
                .plusHours(((Number) rule.get("openStart")).intValue())
                .toInstant()
                .toEpochMilli()
            )
          );
          long b = Math.min(
            end.toEpochMilli(),
            Math.min(
              to,
              date
                .atStartOfDay(zone)
                .plusHours(((Number) rule.get("openEnd")).intValue())
                .toInstant()
                .toEpochMilli()
            )
          );
          if (a < b) eligible.add(new long[] { a, b });
        }
      }
      for (var block : db.rows(
        "SELECT starts_at,ends_at FROM equipment_block WHERE equipment_id=? AND starts_at<? AND (ends_at IS NULL OR ends_at>?)",
        id,
        java.sql.Timestamp.from(end),
        java.sql.Timestamp.from(start)
      )) {
        long a = Instant.parse(str(block, "startsAt")).toEpochMilli(),
          b =
            block.get("endsAt") == null
              ? end.toEpochMilli()
              : Instant.parse(str(block, "endsAt")).toEpochMilli();
        eligible = subtract(eligible, a, b);
      }
      double open =
          eligible
            .stream()
            .mapToLong(x -> x[1] - x[0])
            .sum() / 3600000.0,
        used = 0;
      for (var loan : db.rows(
        "SELECT issued_at,returned_at FROM loan_record WHERE equipment_id=? AND issued_at<? AND (returned_at IS NULL OR returned_at>?)",
        id,
        java.sql.Timestamp.from(end),
        java.sql.Timestamp.from(start)
      )) {
        long a = Instant.parse(str(loan, "issuedAt")).toEpochMilli(),
          b =
            loan.get("returnedAt") == null
              ? end.toEpochMilli()
              : Instant.parse(str(loan, "returnedAt")).toEpochMilli();
        for (var x : eligible)
          used +=
            Math.max(0, Math.min(b, x[1]) - Math.max(a, x[0])) / 3600000.0;
      }
      totalOpen += open;
      totalUsed += used;
      rates.add(
        Map.of(
          "id",
          id,
          "name",
          e.get("name"),
          "assetNo",
          e.get("assetNo"),
          "openHours",
          round(open),
          "usedHours",
          round(used),
          "rate",
          open == 0 ? 0 : round((100 * used) / open)
        )
      );
    }
    var allowedEquipment = eq
      .stream()
      .map(e -> str(e, "id"))
      .collect(java.util.stream.Collectors.toSet());
    var allLoans = loans(u, false)
      .stream()
      .filter(t -> allowedEquipment.contains(str(t, "equipmentId")))
      .toList();
    var ts = tickets(u)
      .stream()
      .filter(t -> allowedEquipment.contains(str(t, "equipmentId")))
      .filter(t -> lab.isEmpty() || str(t, "labId").equals(lab))
      .filter(t -> !Instant.parse(str(t, "createdAt")).isBefore(start))
      .toList();
    var ls = allLoans
      .stream()
      .filter(t -> lab.isEmpty() || str(t, "labId").equals(lab))
      .filter(t -> !Instant.parse(str(t, "issuedAt")).isBefore(start))
      .toList();
    Map<String, Long> category = new LinkedHashMap<>(),
      status = new LinkedHashMap<>();
    for (var e : eq) {
      category.merge(str(e, "categoryName"), 1L, Long::sum);
      status.merge(str(e, "operationalStatus"), 1L, Long::sum);
    }
    List<Map<String, Object>> trend = new ArrayList<>();
    for (int i = Math.min(days, 30) - 1; i >= 0; i--) {
      LocalDate d = end
        .atZone(ZoneId.of("Asia/Shanghai"))
        .toLocalDate()
        .minusDays(i);
      long n = ls
        .stream()
        .filter(x ->
          Instant.parse(str(x, "issuedAt"))
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toLocalDate()
            .equals(d)
        )
        .count();
      trend.add(Map.of("date", d.toString(), "count", n));
    }
    Map<String, Long> faults = new LinkedHashMap<>(),
      ticketStatus = new LinkedHashMap<>();
    double repairHours = 0;
    int repaired = 0;
    for (var t : ts) {
      faults.merge(str(t, "equipmentName"), 1L, Long::sum);
      ticketStatus.merge(str(t, "status"), 1L, Long::sum);
      if (t.get("acceptedAt") != null && t.get("repairSubmittedAt") != null) {
        repairHours +=
          Math.max(
            0,
            Duration.between(
              Instant.parse(str(t, "acceptedAt")),
              Instant.parse(str(t, "repairSubmittedAt"))
            ).toMillis()
          ) / 3600000.0;
        repaired++;
      }
    }
    return Map.ofEntries(
      Map.entry("days", days),
      Map.entry("equipmentCount", eq.size()),
      Map.entry("loanCount", ls.size()),
      Map.entry("ticketCount", ts.size()),
      Map.entry(
        "closedTickets",
        ts
          .stream()
          .filter(t -> str(t, "status").equals("CLOSED"))
          .count()
      ),
      Map.entry("openHours", round(totalOpen)),
      Map.entry("usedHours", round(totalUsed)),
      Map.entry(
        "utilization",
        totalOpen == 0 ? 0 : round((100 * totalUsed) / totalOpen)
      ),
      Map.entry("category", category),
      Map.entry("status", status),
      Map.entry("equipmentRates", rates),
      Map.entry("faultDistribution", faults),
      Map.entry("ticketStatus", ticketStatus),
      Map.entry(
        "averageRepairHours",
        repaired == 0 ? 0 : round(repairHours / repaired)
      ),
      Map.entry("repairSamples", repaired),
      Map.entry(
        "currentOverdue",
        allLoans
          .stream()
          .filter(l -> bool(l.get("overdue")))
          .count()
      ),
      Map.entry(
        "currentBorrowed",
        allLoans
          .stream()
          .filter(l -> l.get("returnedAt") == null)
          .count()
      ),
      Map.entry("trend", trend),
      Map.entry("asOf", end.toString()),
      Map.entry(
        "definition",
        "实际借用与开放时段交集 ÷ 扣除停用、维修区间并集后的开放时长；未归还截至统计时刻。无开放时长时比例记为 0 并单独展示分母。"
      )
    );
  }

  static List<long[]> subtract(List<long[]> source, long a, long b) {
    List<long[]> out = new ArrayList<>();
    for (var x : source) {
      if (b <= x[0] || a >= x[1]) out.add(x);
      else {
        if (a > x[0]) out.add(new long[] { x[0], a });
        if (b < x[1]) out.add(new long[] { b, x[1] });
      }
    }
    return out;
  }

  static double round(double n) {
    return Math.round(n * 100.0) / 100.0;
  }
}
