package com.lab.equipment;

import static com.lab.equipment.ApiError.require;
import static com.lab.equipment.Db.*;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Business {

  final Db db;
  final Auth auth;
  final Queries q;
  final Files files;

  Business(Db db, Auth auth, Queries q, Files files) {
    this.db = db;
    this.auth = auth;
    this.q = q;
    this.files = files;
  }

  Map<String, Object> lock(String id) {
    return db.one("SELECT * FROM equipment WHERE id=? FOR UPDATE", id);
  }

  String statusName(String code) {
    return switch (code) {
      case "APPROVED" -> "已批准";
      case "REJECTED" -> "已驳回";
      case "CANCELLED" -> "已取消";
      case "VERIFIED" -> "已核实";
      case "ASSIGNED" -> "已分派";
      case "ACCEPTED" -> "已受理";
      case "REPAIRING" -> "维修中";
      case "PENDING_ACCEPTANCE" -> "待验收";
      case "CLOSED" -> "已关闭";
      default -> code;
    };
  }

  void usable(Map<String, Object> e) {
    require(bool(e.get("enabled")), "设备已停用");
    require(
      db.count(
        "SELECT COUNT(*) FROM equipment_block WHERE equipment_id=? AND ends_at IS NULL",
        e.get("id")
      ) == 0,
      "设备存在未解除的故障或停用限制"
    );
  }

  Instant time(Map<String, Object> b, String key) {
    try {
      return OffsetDateTime.parse(required(b, key, 60))
        .toInstant()
        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    } catch (ApiError e) {
      throw e;
    } catch (Exception e) {
      throw new ApiError(422, "INVALID_TIME", "请提供带时区的起止时间");
    }
  }

  void interval(
    Map<String, Object> e,
    Instant start,
    Instant end,
    String excluding
  ) {
    require(start.isBefore(end), "结束时间必须晚于开始时间");
    require(Duration.between(start, end).toHours() <= 168, "单次预约最长 7 天");
    require(
      start.isBefore(Instant.now().plus(Duration.ofDays(14))),
      "最多提前 14 天预约"
    );
    var l = db.one(
      "SELECT * FROM laboratory WHERE id=? AND enabled=TRUE",
      e.get("labId")
    );
    ZoneId zone = ZoneId.of("Asia/Shanghai");
    var a = start.atZone(zone);
    var b = end.atZone(zone);
    require(
      a.toLocalDate().equals(b.minusNanos(1).toLocalDate()),
      "请按单日开放时间申请，跨日使用请分开预约"
    );
    require(
      !a
        .toLocalTime()
        .isBefore(LocalTime.of(((Number) l.get("openStart")).intValue(), 0)),
      "申请时间早于实验室开放时间"
    );
    long endOfDay = a
      .toLocalDate()
      .atStartOfDay(zone)
      .plusHours(((Number) l.get("openEnd")).intValue())
      .toInstant()
      .toEpochMilli();
    require(end.toEpochMilli() <= endOfDay, "申请时间晚于实验室关闭时间");
    require(
      db.count(
        "SELECT COUNT(*) FROM lab_calendar WHERE lab_id=? AND local_date=? AND is_open=FALSE",
        e.get("labId"),
        a.toLocalDate()
      ) == 0,
      "该日期实验室不开放"
    );
    require(
      db.count(
        "SELECT COUNT(*) FROM reservation WHERE equipment_id=? AND status='APPROVED' AND starts_at<? AND ends_at>? AND id<>?",
        e.get("id"),
        Timestamp.from(end),
        Timestamp.from(start),
        excluding
      ) == 0,
      "该时段已有获批预约，请选择其他时间"
    );
    require(
      db.count(
        "SELECT COUNT(*) FROM loan_record WHERE equipment_id=? AND returned_at IS NULL AND (due_at>? OR due_at<UTC_TIMESTAMP(3))",
        e.get("id"),
        Timestamp.from(start)
      ) == 0,
      "设备尚未归还或预计归还时间冲突"
    );
  }

  Map<String, Object> previous(
    User u,
    String op,
    String key,
    Map<String, Object> body
  ) {
    if (key == null || key.isBlank()) throw new ApiError(
      422,
      "IDEMPOTENCY_REQUIRED",
      "缺少请求标识，请刷新后重试"
    );
    if (key.length() > 100) throw new ApiError(
      422,
      "INVALID_KEY",
      "请求标识过长"
    );
    var rows = db.rows(
      "SELECT request_hash,response_json FROM idempotency_record WHERE user_id=? AND operation=? AND idempotency_key=?",
      u.id(),
      op,
      key
    );
    if (rows.isEmpty()) return null;
    require(
      str(rows.getFirst(), "requestHash").equals(hash(db.encode(body))),
      "同一请求标识不能用于不同内容"
    );
    return db.object(str(rows.getFirst(), "responseJson"));
  }

  void remember(
    User u,
    String op,
    String key,
    Map<String, Object> body,
    Map<String, Object> response
  ) {
    db.update(
      "INSERT INTO idempotency_record VALUES(?,?,?,?,?,?)",
      u.id(),
      op,
      key,
      hash(db.encode(body)),
      db.encode(response),
      now()
    );
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Map<String, Object> reserve(
    User u,
    Map<String, Object> b,
    String key
  ) {
    String eid = required(b, "equipmentId", 36);
    var e = lock(eid);
    var old = previous(u, "reserve", key, b);
    if (old != null) return old;
    usable(e);
    Instant start = time(b, "startsAt"),
      end = time(b, "endsAt");
    require(
      end.isAfter(Instant.now()) &&
        !start.isBefore(Instant.now().minusSeconds(60)),
      "不能预约已经过去的时段"
    );
    interval(e, start, end, "");
    String id = id();
    db.update(
      "INSERT INTO reservation(id,reservation_no,equipment_id,user_id,lab_id,starts_at,ends_at,purpose,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
      id,
      "YY-" + id.substring(0, 8).toUpperCase(),
      eid,
      u.id(),
      e.get("labId"),
      Timestamp.from(start),
      Timestamp.from(end),
      required(b, "purpose", 600),
      "PENDING",
      now()
    );
    db.event("RESERVATION", id, "提交预约", u.id(), "等待实验员审核");
    for (var s : db.rows(
      "SELECT user_id FROM lab_staff_scope WHERE lab_id=?",
      e.get("labId")
    ))
      db.notify(
        str(s, "userId"),
        "新的设备预约",
        u.name() + "申请借用" + e.get("name"),
        "RESERVATION",
        id,
        id + ":new"
      );
    var result = q.reservation(u, id);
    remember(u, "reserve", key, b, result);
    return result;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Map<String, Object> reservationAction(
    User u,
    String id,
    String action,
    Map<String, Object> b
  ) {
    var initial = db.one("SELECT * FROM reservation WHERE id=?", id);
    var e = lock(str(initial, "equipmentId"));
    var r = db.one("SELECT * FROM reservation WHERE id=? FOR UPDATE", id);
    String state = str(r, "status");
    String next;
    if (action.equals("cancel")) {
      require(
        str(r, "userId").equals(u.id()) || u.staff(),
        "不能取消他人的预约"
      );
      if (!str(r, "userId").equals(u.id())) auth.lab(u, str(r, "labId"));
      require(
        Set.of("PENDING", "APPROVED").contains(state),
        "当前预约不能取消"
      );
      next = "CANCELLED";
    } else {
      auth.lab(u, str(r, "labId"));
      require(!str(r, "userId").equals(u.id()), "不能审核自己的预约");
      require(state.equals("PENDING"), "只有待审核预约可以审批");
      if (action.equals("approve")) {
        usable(e);
        require(
          Instant.parse(str(r, "endsAt")).isAfter(Instant.now()),
          "预约时间已过"
        );
        interval(
          e,
          Instant.parse(str(r, "startsAt")),
          Instant.parse(str(r, "endsAt")),
          id
        );
        next = "APPROVED";
      } else if (action.equals("reject")) {
        required(b, "reason", 600);
        next = "REJECTED";
      } else throw new ApiError(404, "NOT_FOUND", "不支持的预约操作");
    }
    db.update(
      "UPDATE reservation SET status=?,reviewed_by=?,reason=?,version=version+1 WHERE id=?",
      next,
      u.id(),
      str(b, "reason"),
      id
    );
    db.event("RESERVATION", id, next, u.id(), str(b, "reason"));
    db.notify(
      str(r, "userId"),
      "预约状态更新",
      e.get("name") + "：" + statusName(next),
      "RESERVATION",
      id,
      id + ":" + next
    );
    return q.reservation(u, id);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Map<String, Object> issue(User u, String rid, String key) {
    var first = db.one("SELECT * FROM reservation WHERE id=?", rid);
    var e = lock(str(first, "equipmentId"));
    auth.lab(u, str(first, "labId"));
    var body = Map.<String, Object>of("reservationId", rid);
    var old = previous(u, "issue", key, body);
    if (old != null) return old;
    var r = db.one("SELECT * FROM reservation WHERE id=? FOR UPDATE", rid);
    require(str(r, "status").equals("APPROVED"), "预约尚未获批或已失效");
    usable(e);
    Instant now = Instant.now(),
      start = Instant.parse(str(r, "startsAt")),
      end = Instant.parse(str(r, "endsAt"));
    require(
      !now.isBefore(start.minusSeconds(900)) &&
        !now.isAfter(start.plusSeconds(1800)) &&
        now.isBefore(end),
      "请在预约开始前 15 分钟至开始后 30 分钟内发放"
    );
    require(
      db.count(
        "SELECT COUNT(*) FROM loan_record WHERE equipment_id=? AND returned_at IS NULL",
        e.get("id")
      ) == 0,
      "设备尚未归还，不能重复发放"
    );
    require(
      db.count(
        "SELECT COUNT(*) FROM reservation WHERE equipment_id=? AND id<>? AND status='APPROVED' AND starts_at<? AND ends_at>?",
        e.get("id"),
        rid,
        Timestamp.from(end),
        Timestamp.from(now)
      ) == 0,
      "提前领取会占用其他预约时间"
    );
    String id = id();
    db.update(
      "INSERT INTO loan_record(id,loan_no,reservation_id,equipment_id,user_id,lab_id,issued_by,issued_at,due_at,status) VALUES(?,?,?,?,?,?,?,?,?,?)",
      id,
      "JY-" + id.substring(0, 8).toUpperCase(),
      rid,
      e.get("id"),
      r.get("userId"),
      r.get("labId"),
      u.id(),
      Timestamp.from(now),
      Timestamp.from(end),
      "BORROWED"
    );
    db.update(
      "UPDATE reservation SET status='FULFILLED',version=version+1 WHERE id=?",
      rid
    );
    db.event("LOAN", id, "实物发放", u.id(), "已核对设备与借用人");
    db.event("RESERVATION", rid, "已发放", u.id(), id);
    db.notify(
      str(r, "userId"),
      "设备已借出",
      e.get("name") + "，请按时归还",
      "LOAN",
      id,
      id + ":issue"
    );
    var result = q.loan(u, id);
    remember(u, "issue", key, body, result);
    return result;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Map<String, Object> returnLoan(
    User u,
    String lid,
    Map<String, Object> b,
    String key
  ) {
    var first = db.one("SELECT * FROM loan_record WHERE id=?", lid);
    lock(str(first, "equipmentId"));
    require(str(first, "userId").equals(u.id()), "只能归还本人借用的设备");
    var old = previous(u, "return:" + lid, key, b);
    if (old != null) return old;
    var loan = db.one("SELECT * FROM loan_record WHERE id=? FOR UPDATE", lid);
    require(loan.get("returnedAt") == null, "设备已经归还");
    var photos = strings(b.get("photoIds"));
    require(!photos.isEmpty(), "请上传至少一张归还照片");
    require(photos.size() <= 6, "最多上传 6 张归还照片");
    for (String photo : photos) files.requireOwnedImage(u, photo);
    db.update(
      "UPDATE loan_record SET status='RETURNED',returned_at=?,return_note=?,return_photo_ids=? WHERE id=?",
      now(),
      str(b, "note"),
      db.encode(photos),
      lid
    );
    db.event("LOAN", lid, "照片归还", u.id(), str(b, "note"));
    db.notify(
      u.id(),
      "设备归还成功",
      "归还照片已保存，实验员可进行后续检查",
      "LOAN",
      lid,
      lid + ":return"
    );
    for (var s : db.rows(
      "SELECT user_id FROM lab_staff_scope WHERE lab_id=?",
      loan.get("labId")
    ))
      db.notify(
        str(s, "userId"),
        "设备已归还，待检查",
        u.name() + "已提交设备归还照片",
        "LOAN",
        lid,
        lid + ":check"
      );
    var result = q.loan(u, lid);
    if (bool(b.get("damaged"))) {
      Map<String, Object> t = new HashMap<>();
      t.put("equipmentId", loan.get("equipmentId"));
      t.put("loanId", lid);
      t.put("title", "归还时报告异常");
      t.put("description", required(b, "faultDescription", 4000));
      t.put("photoIds", photos);
      createTicket(u, t);
    }
    remember(u, "return:" + lid, key, b, result);
    return result;
  }

  @Transactional
  public Map<String, Object> checkReturn(User u, String lid) {
    var first = db.one("SELECT * FROM loan_record WHERE id=?", lid);
    lock(str(first, "equipmentId"));
    auth.lab(u, str(first, "labId"));
    require(first.get("returnedAt") != null, "设备尚未归还");
    db.update(
      "UPDATE loan_record SET checked_by=?,checked_at=? WHERE id=?",
      u.id(),
      now(),
      lid
    );
    db.event("LOAN", lid, "归还检查完成", u.id(), "照片及设备状况已核对");
    return q.loan(u, lid);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Map<String, Object> saveEquipment(
    User u,
    String id,
    Map<String, Object> b
  ) {
    String lab = required(b, "labId", 36);
    auth.lab(u, lab);
    String name = required(b, "name", 120),
      model = required(b, "model", 120),
      asset = required(b, "assetNo", 60),
      cat = required(b, "categoryId", 36);
    db.one("SELECT id FROM equipment_category WHERE id=?", cat);
    String date = str(b, "purchasedOn");
    if (id == null) {
      id = id();
      db.update(
        "INSERT INTO equipment(id,asset_no,name,model,category_id,lab_id,purchased_on,description,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
        id,
        asset,
        name,
        model,
        cat,
        lab,
        date.isEmpty() ? null : date,
        str(b, "description"),
        now()
      );
    } else {
      var e = lock(id);
      auth.lab(u, str(e, "labId"));
      require(
        str(e, "labId").equals(lab),
        "设备归属调整需先归还并处理所有预约，本版不直接迁移历史设备"
      );
      db.update(
        "UPDATE equipment SET asset_no=?,name=?,model=?,category_id=?,purchased_on=?,description=?,version=version+1 WHERE id=?",
        asset,
        name,
        model,
        cat,
        date.isEmpty() ? null : date,
        str(b, "description"),
        id
      );
    }
    db.event("EQUIPMENT", id, "维护设备档案", u.id(), name);
    db.audit(u.id(), "SAVE_EQUIPMENT", id, name);
    return q.equipment(u, id);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Map<String, Object> toggleEquipment(
    User u,
    String id,
    Map<String, Object> b
  ) {
    var e = lock(id);
    auth.lab(u, str(e, "labId"));
    boolean enabled = bool(b.get("enabled"));
    String reason = required(b, "reason", 600);
    if (enabled) {
      db.update(
        "UPDATE equipment_block SET ends_at=? WHERE equipment_id=? AND source_type='MANUAL' AND ends_at IS NULL",
        now(),
        id
      );
    } else {
      if (bool(e.get("enabled"))) {
        db.update(
          "INSERT INTO equipment_block VALUES(?,?,?,?,?,?,NULL)",
          id(),
          id,
          "MANUAL",
          id(),
          reason,
          now()
        );
        invalidate(u, id, reason);
      }
    }
    db.update(
      "UPDATE equipment SET enabled=?,version=version+1 WHERE id=?",
      enabled,
      id
    );
    db.event("EQUIPMENT", id, enabled ? "启用" : "停用", u.id(), reason);
    db.audit(u.id(), "TOGGLE_EQUIPMENT", id, reason);
    return q.equipment(u, id);
  }

  void invalidate(User u, String eid, String reason) {
    for (var r : db.rows(
      "SELECT id,user_id FROM reservation WHERE equipment_id=? AND status='APPROVED'",
      eid
    )) {
      db.update(
        "UPDATE reservation SET status='INVALIDATED',reason=?,version=version+1 WHERE id=?",
        reason,
        r.get("id")
      );
      db.event("RESERVATION", str(r, "id"), "设备禁借导致失效", u.id(), reason);
      db.notify(
        str(r, "userId"),
        "预约因设备异常失效",
        reason,
        "RESERVATION",
        str(r, "id"),
        r.get("id") + ":invalidated"
      );
    }
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Map<String, Object> createTicket(
    User u,
    Map<String, Object> b,
    String key
  ) {
    lock(required(b, "equipmentId", 36));
    var old = previous(u, "report", key, b);
    if (old != null) return old;
    var result = createTicket(u, b);
    remember(u, "report", key, b, result);
    return result;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Map<String, Object> createTicket(User u, Map<String, Object> b) {
    String eid = required(b, "equipmentId", 36);
    var e = lock(eid);
    var photos = strings(b.get("photoIds"));
    for (var photo : photos) files.requireOwnedImage(u, photo);
    String loan = str(b, "loanId");
    if (!loan.isEmpty()) {
      var l = q.loan(u, loan);
      require(str(l, "equipmentId").equals(eid), "借用记录与设备不匹配");
    }
    String id = id();
    db.update(
      "INSERT INTO fault_ticket(id,ticket_no,equipment_id,lab_id,loan_id,reporter_id,title,description,photo_ids,severity,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
      id,
      "GD-" + id.substring(0, 8).toUpperCase(),
      eid,
      e.get("labId"),
      loan.isEmpty() ? null : loan,
      u.id(),
      required(b, "title", 160),
      required(b, "description", 4000),
      db.encode(photos),
      str(b, "severity").equals("URGENT") ? "URGENT" : "NORMAL",
      "SUBMITTED",
      now()
    );
    db.event("TICKET", id, "提交报修", u.id(), str(b, "description"));
    for (var s : db.rows(
      "SELECT user_id FROM lab_staff_scope WHERE lab_id=?",
      e.get("labId")
    ))
      db.notify(
        str(s, "userId"),
        "新的设备报修",
        str(b, "title"),
        "TICKET",
        id,
        id + ":new"
      );
    return q.ticket(u, id);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Map<String, Object> ticketAction(
    User u,
    String id,
    String action,
    Map<String, Object> b
  ) {
    var visible = q.ticket(u, id);
    lock(str(visible, "equipmentId"));
    var t = db.one("SELECT * FROM fault_ticket WHERE id=? FOR UPDATE", id);
    String status = str(t, "status"),
      next = status,
      note = str(b, "note");
    String eid = str(t, "equipmentId");
    switch (action) {
      case "verify" -> {
        auth.lab(u, str(t, "labId"));
        require(status.equals("SUBMITTED"), "只有待核实工单可以核实");
        require(b.containsKey("affectsUse"), "请选择是否影响使用");
        boolean affects = bool(b.get("affectsUse"));
        db.update(
          "UPDATE fault_ticket SET affects_use=?,verified_by=? WHERE id=?",
          affects,
          u.id(),
          id
        );
        if (affects) {
          db.update(
            "INSERT INTO equipment_block VALUES(?,?,?,?,?,?,NULL)",
            Db.id(),
            eid,
            "FAULT",
            id,
            str(t, "title"),
            now()
          );
          invalidate(u, eid, "设备故障：" + t.get("title"));
          db.event("EQUIPMENT", eid, "故障禁借", u.id(), str(t, "title"));
        }
        next = "VERIFIED";
      }
      case "reject" -> {
        auth.lab(u, str(t, "labId"));
        require(status.equals("SUBMITTED"), "只有待核实工单可以驳回");
        required(b, "note", 2000);
        next = "REJECTED";
      }
      case "cancel" -> {
        require(str(t, "reporterId").equals(u.id()), "只能撤回本人报修");
        require(status.equals("SUBMITTED"), "已核实工单不能自行撤回");
        next = "CANCELLED";
      }
      case "assign" -> {
        auth.lab(u, str(t, "labId"));
        require(
          Set.of("VERIFIED", "ASSIGNED", "ACCEPTED", "REPAIRING").contains(
            status
          ),
          "当前工单不能分派"
        );
        String assignee = required(b, "assigneeId", 36);
        require(auth.user(assignee).tech(), "请选择有效维修人员");
        db.update(
          "UPDATE fault_ticket SET assignee_id=? WHERE id=?",
          assignee,
          id
        );
        next = "ASSIGNED";
        db.notify(
          assignee,
          "新的维修工单",
          str(t, "title"),
          "TICKET",
          id,
          id + ":assign:" + Db.id()
        );
      }
      case "note" -> {
        require(
          str(t, "assigneeId").equals(u.id()) && u.tech(),
          "只有当前维修人员可以记录"
        );
        require(
          Set.of("ACCEPTED", "REPAIRING").contains(status),
          "当前状态不能添加维修记录"
        );
        required(b, "note", 2000);
      }
      case
        "accept",
        "start-repair",
        "submit-acceptance",
        "return-assignment" -> {
        require(
          str(t, "assigneeId").equals(u.id()) && u.tech(),
          "只有当前维修人员可以执行"
        );
        if (action.equals("accept")) {
          require(status.equals("ASSIGNED"), "请先等待分派");
          next = "ACCEPTED";
          db.update(
            "UPDATE fault_ticket SET accepted_at=? WHERE id=?",
            now(),
            id
          );
        } else if (action.equals("start-repair")) {
          require(status.equals("ACCEPTED"), "请先受理工单");
          next = "REPAIRING";
          db.update(
            "UPDATE fault_ticket SET repair_started_at=? WHERE id=?",
            now(),
            id
          );
        } else if (action.equals("return-assignment")) {
          require(
            Set.of("ASSIGNED", "ACCEPTED").contains(status),
            "当前状态不能退回分派"
          );
          required(b, "note", 2000);
          next = "VERIFIED";
          db.update("UPDATE fault_ticket SET assignee_id=NULL WHERE id=?", id);
        } else {
          require(status.equals("REPAIRING"), "请先开始维修");
          String resolution = required(b, "resolution", 4000);
          int minutes = ((Number) b.getOrDefault("laborMinutes", 0)).intValue();
          double cost = (
            (Number) b.getOrDefault("costAmount", 0)
          ).doubleValue();
          require(minutes >= 0 && cost >= 0, "工时和费用不能为负数");
          next = "PENDING_ACCEPTANCE";
          db.update(
            "UPDATE fault_ticket SET resolution=?,labor_minutes=labor_minutes+?,cost_amount=cost_amount+?,repair_submitted_at=? WHERE id=?",
            resolution,
            minutes,
            cost,
            now(),
            id
          );
          note = resolution;
        }
      }
      case "acceptance" -> {
        auth.lab(u, str(t, "labId"));
        require(status.equals("PENDING_ACCEPTANCE"), "工单尚未提交验收");
        require(!str(t, "assigneeId").equals(u.id()), "不能验收自己负责的维修");
        boolean passed = bool(b.get("passed"));
        required(b, "note", 2000);
        next = passed ? "CLOSED" : "REPAIRING";
        if (passed) {
          db.update(
            "UPDATE fault_ticket SET closed_at=? WHERE id=?",
            now(),
            id
          );
          db.update(
            "UPDATE equipment_block SET ends_at=? WHERE source_type='FAULT' AND source_id=? AND ends_at IS NULL",
            now(),
            id
          );
          db.event("EQUIPMENT", eid, "解除本工单故障限制", u.id(), note);
        }
      }
      default -> throw new ApiError(404, "NOT_FOUND", "不支持的工单操作");
    }
    db.update(
      "UPDATE fault_ticket SET status=?,version=version+1 WHERE id=?",
      next,
      id
    );
    db.event("TICKET", id, action + " → " + next, u.id(), note);
    db.notify(
      str(t, "reporterId"),
      "工单进度更新",
      t.get("title") + "：" + statusName(next),
      "TICKET",
      id,
      id + ":" + Db.id()
    );
    db.audit(u.id(), "TICKET_" + action, id, note);
    return q.ticket(u, id);
  }

  @Scheduled(fixedDelay = 60000)
  public void reminders() {
    try {
      for (var l : db.rows(
        "SELECT * FROM loan_record WHERE returned_at IS NULL AND due_at<DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 24 HOUR)"
      )) {
        boolean overdue = Instant.parse(str(l, "dueAt")).isBefore(
          Instant.now()
        );
        db.notify(
          str(l, "userId"),
          overdue ? "设备已逾期" : "设备即将到期",
          "请上传归还照片并完成归还",
          "LOAN",
          str(l, "id"),
          l.get("id") +
            ":" +
            (overdue ? "overdue:" : "due:") +
            LocalDate.now(ZoneId.of("Asia/Shanghai"))
        );
      }
    } catch (Exception ignored) {}
  }

  @Transactional
  @Scheduled(fixedDelay = 60000)
  public void expireReservations() {
    try {
      for (var r : db.rows(
        "SELECT id,equipment_id FROM reservation WHERE (status='PENDING' AND ends_at<UTC_TIMESTAMP(3)) OR (status='APPROVED' AND (DATE_ADD(starts_at,INTERVAL 30 MINUTE)<UTC_TIMESTAMP(3) OR ends_at<UTC_TIMESTAMP(3))) ORDER BY equipment_id LIMIT 100"
      )) {
        lock(str(r, "equipmentId"));
        if (
          db.update(
            "UPDATE reservation SET status='EXPIRED',version=version+1 WHERE id=? AND status IN ('PENDING','APPROVED')",
            r.get("id")
          ) > 0
        ) db.event(
          "RESERVATION",
          str(r, "id"),
          "预约过期",
          "SYSTEM",
          "未按时领取或审批"
        );
      }
    } catch (Exception ignored) {}
  }
}
