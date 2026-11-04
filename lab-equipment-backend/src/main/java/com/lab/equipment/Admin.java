package com.lab.equipment;

import static com.lab.equipment.ApiError.require;
import static com.lab.equipment.Db.*;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Admin {

  final Db db;
  final Auth auth;

  Admin(Db db, Auth auth) {
    this.db = db;
    this.auth = auth;
  }

  List<Map<String, Object>> users(User u) {
    auth.admin(u);
    var rows = db.rows(
      "SELECT id,username,name,role,enabled,created_at FROM sys_user ORDER BY created_at"
    );
    for (var r : rows) {
      r.put(
        "roles",
        db
          .rows(
            "SELECT role_code FROM sys_user_role WHERE user_id=?",
            r.get("id")
          )
          .stream()
          .map(x -> x.get("roleCode"))
          .toList()
      );
      r.put(
        "labIds",
        db
          .rows(
            "SELECT lab_id FROM lab_staff_scope WHERE user_id=?",
            r.get("id")
          )
          .stream()
          .map(x -> x.get("labId"))
          .toList()
      );
    }
    return rows;
  }

  @Transactional
  public Map<String, Object> saveUser(
    User actor,
    String id,
    Map<String, Object> b
  ) {
    auth.admin(actor);
    var roles = strings(b.get("roles"));
    require(
      !roles.isEmpty() &&
        Set.of("ADMIN", "STUDENT", "LAB_STAFF", "TECHNICIAN").containsAll(
          roles
        ),
      "请选择有效角色"
    );
    boolean enabled = !b.containsKey("enabled") || bool(b.get("enabled"));
    if (id != null && id.equals(actor.id())) require(
      enabled && roles.contains("ADMIN"),
      "不能停用自己或移除自己的管理员权限"
    );
    String name = required(b, "name", 80);
    String password = str(b, "password");
    if (id == null) {
      require(
        password.length() >= 6 && password.length() <= 100,
        "初始密码长度为 6 至 100 位"
      );
      id = id();
      db.update(
        "INSERT INTO sys_user(id,username,password_hash,name,role,enabled,created_at) VALUES(?,?,?,?,?,?,?)",
        id,
        required(b, "username", 80),
        auth.passwords.encode(password),
        name,
        roles.getFirst(),
        enabled,
        now()
      );
    } else {
      db.one("SELECT id FROM sys_user WHERE id=?", id);
      db.update(
        "UPDATE sys_user SET name=?,role=?,enabled=?,auth_version=auth_version+1 WHERE id=?",
        name,
        roles.getFirst(),
        enabled,
        id
      );
      if (!password.isEmpty()) {
        require(
          password.length() >= 6 && password.length() <= 100,
          "密码长度为 6 至 100 位"
        );
        db.update(
          "UPDATE sys_user SET password_hash=? WHERE id=?",
          auth.passwords.encode(password),
          id
        );
      }
    }
    db.update("DELETE FROM sys_user_role WHERE user_id=?", id);
    for (String role : roles)
      db.update("INSERT INTO sys_user_role VALUES(?,?)", id, role);
    db.update("DELETE FROM lab_staff_scope WHERE user_id=?", id);
    for (String lab : strings(b.get("labIds"))) {
      db.one("SELECT id FROM laboratory WHERE id=?", lab);
      db.update("INSERT INTO lab_staff_scope VALUES(?,?)", id, lab);
    }
    db.audit(actor.id(), "SAVE_USER", id, "角色：" + String.join(",", roles));
    return Map.of("id", id);
  }

  @Transactional
  public Map<String, Object> saveLab(User u, String id, Map<String, Object> b) {
    auth.admin(u);
    String name = required(b, "name", 100),
      location = required(b, "location", 160);
    int start = ((Number) b.getOrDefault("openStart", 8)).intValue(),
      end = ((Number) b.getOrDefault("openEnd", 22)).intValue();
    require(start >= 0 && end <= 24 && start < end, "开放时间设置无效");
    if (id == null) {
      id = id();
      db.update(
        "INSERT INTO laboratory(id,name,location,open_start,open_end,created_at) VALUES(?,?,?,?,?,?)",
        id,
        name,
        location,
        start,
        end,
        now()
      );
      db.update(
        "INSERT INTO lab_open_rule VALUES(?,?,?,?,?,NULL)",
        Db.id(),
        id,
        start,
        end,
        now()
      );
    } else {
      var previous = db.one(
        "SELECT * FROM laboratory WHERE id=? FOR UPDATE",
        id
      );
      if (
        ((Number) previous.get("openStart")).intValue() != start ||
        ((Number) previous.get("openEnd")).intValue() != end
      ) {
        db.update(
          "UPDATE lab_open_rule SET effective_to=? WHERE lab_id=? AND effective_to IS NULL",
          now(),
          id
        );
        db.update(
          "INSERT INTO lab_open_rule VALUES(?,?,?,?,?,NULL)",
          Db.id(),
          id,
          start,
          end,
          now()
        );
      }
      db.update(
        "UPDATE laboratory SET name=?,location=?,open_start=?,open_end=? WHERE id=?",
        name,
        location,
        start,
        end,
        id
      );
    }
    db.audit(u.id(), "SAVE_LAB", id, name);
    return db.one("SELECT * FROM laboratory WHERE id=?", id);
  }

  @Transactional
  public void changePassword(User u, Map<String, Object> b) {
    String old = required(b, "oldPassword", 100),
      password = required(b, "newPassword", 100);
    require(password.length() >= 6, "新密码至少 6 位");
    var user = db.one("SELECT password_hash FROM sys_user WHERE id=?", u.id());
    require(
      auth.passwords.matches(old, str(user, "passwordHash")),
      "原密码不正确"
    );
    db.update(
      "UPDATE sys_user SET password_hash=?,auth_version=auth_version+1 WHERE id=?",
      auth.passwords.encode(password),
      u.id()
    );
    db.audit(u.id(), "CHANGE_PASSWORD", u.id(), "所有旧会话失效");
  }
}

@Configuration
class Seed {

  @Bean
  ApplicationRunner initializeData(Db db, Auth auth, Environment env) {
    return args -> {
      for (var r : Map.of(
        "STUDENT",
        "学生",
        "LAB_STAFF",
        "实验员",
        "TECHNICIAN",
        "维修人员",
        "ADMIN",
        "管理员"
      ).entrySet())
        db.update(
          "INSERT IGNORE INTO sys_role VALUES(?,?)",
          r.getKey(),
          r.getValue()
        );
      for (String p : List.of(
        "equipment:read",
        "equipment:write",
        "reservation:create",
        "reservation:approve",
        "loan:issue",
        "loan:return",
        "ticket:report",
        "ticket:repair",
        "ticket:acceptance",
        "statistics:read",
        "knowledge:manage",
        "users:manage"
      )) {
        db.update("INSERT IGNORE INTO sys_permission VALUES(?,?)", p, p);
        db.update(
          "INSERT IGNORE INTO sys_role_permission VALUES('ADMIN',?)",
          p
        );
      }
      String admin = env.getProperty("SEED_ADMIN_USERNAME", "admin"),
        password = env.getProperty("SEED_ADMIN_PASSWORD", "123456");
      if (
        db.count("SELECT COUNT(*) FROM sys_user WHERE username=?", admin) == 0
      ) {
        if (password.length() < 6) throw new IllegalStateException(
          "首次启动需要设置至少6位 SEED_ADMIN_PASSWORD"
        );
        String uid = id();
        db.update(
          "INSERT INTO sys_user(id,username,password_hash,name,role,created_at) VALUES(?,?,?,?,?,?)",
          uid,
          admin,
          auth.passwords.encode(password),
          "系统管理员",
          "ADMIN",
          now()
        );
        db.update("INSERT INTO sys_user_role VALUES(?,'ADMIN')", uid);
      }
      db.update(
        "UPDATE ai_run SET status='INTERRUPTED',error='服务重启，执行已中断，请重新发送',finished_at=?,grant_hash=NULL WHERE status IN ('QUEUED','RUNNING')",
        now()
      );
      db.update(
        "UPDATE job_task SET status='INTERRUPTED',error='服务重启，可手动重试',updated_at=? WHERE status IN ('QUEUED','RUNNING')",
        now()
      );
      db.update(
        "UPDATE knowledge_version SET status='FAILED',error='服务重启，索引中断，请重新建立索引' WHERE status='INDEXING'"
      );
      if (
        !env.getProperty("SEED_DEMO_DATA", "false").equals("true") ||
        db.count("SELECT COUNT(*) FROM laboratory") > 0
      ) return;
      String demoPassword = env.getProperty(
        "SEED_DEMO_PASSWORD",
        "123456"
      );
      Map<String, String> users = new LinkedHashMap<>();
      for (var entry : Map.of(
        "student",
        "STUDENT",
        "labstaff",
        "LAB_STAFF",
        "technician",
        "TECHNICIAN"
      ).entrySet()) {
        String uid = id();
        String name = switch (entry.getKey()) {
          case "student" -> "学生";
          case "labstaff" -> "实验员";
          default -> "维修人员";
        };
        db.update(
          "INSERT INTO sys_user(id,username,password_hash,name,role,created_at) VALUES(?,?,?,?,?,?)",
          uid,
          entry.getKey(),
          auth.passwords.encode(demoPassword),
          name,
          entry.getValue(),
          now()
        );
        db.update(
          "INSERT INTO sys_user_role VALUES(?,?)",
          uid,
          entry.getValue()
        );
        users.put(entry.getKey(), uid);
      }
      List<String> labs = new ArrayList<>();
      for (var l : List.of(
        new String[] { "电子与测量实验室", "实验楼 A305" },
        new String[] { "智能制造实验室", "实验楼 B201" }
      )) {
        String id = id();
        db.update(
          "INSERT INTO laboratory(id,name,location,created_at) VALUES(?,?,?,?)",
          id,
          l[0],
          l[1],
          Timestamp.from(Instant.now().minus(Duration.ofDays(45)))
        );
        db.update(
          "INSERT INTO lab_open_rule VALUES(?,?,8,22,?,NULL)",
          Db.id(),
          id,
          Timestamp.from(Instant.now().minus(Duration.ofDays(45)))
        );
        db.update(
          "INSERT INTO lab_staff_scope VALUES(?,?)",
          users.get("labstaff"),
          id
        );
        labs.add(id);
      }
      Map<String, String> cats = new LinkedHashMap<>();
      for (String name : List.of(
        "测量仪器",
        "开发与计算",
        "影像设备",
        "机械工具"
      )) {
        String id = id();
        db.update("INSERT INTO equipment_category VALUES(?,?)", id, name);
        cats.put(name, id);
      }
      String[][] devices = {
        { "数字示波器", "Rigol DS1102Z-E", "测量仪器" },
        { "数字万用表", "Fluke 17B+", "测量仪器" },
        { "信号发生器", "Rigol DG1022Z", "测量仪器" },
        { "直流稳压电源", "RIGOL DP832", "测量仪器" },
        { "树莓派开发套件", "Raspberry Pi 5", "开发与计算" },
        { "单片机开发板", "STM32F407", "开发与计算" },
        { "便携工作站", "ThinkPad P16", "开发与计算" },
        { "数字逻辑分析仪", "DSLogic Plus", "测量仪器" },
        { "微单相机", "Sony A6400", "影像设备" },
        { "三轴稳定器", "DJI RS 3 Mini", "影像设备" },
        { "热成像仪", "FLIR C5", "测量仪器" },
        { "精密电烙铁", "HAKKO FX-888D", "机械工具" },
      };
      List<String> eids = new ArrayList<>();
      int i = 0;
      for (var e : devices) {
        String id = id();
        eids.add(id);
        db.update(
          "INSERT INTO equipment(id,asset_no,name,model,category_id,lab_id,purchased_on,description,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
          id,
          "LAB-" + String.format("%04d", ++i),
          e[0],
          e[1],
          cats.get(e[2]),
          labs.get(i <= 8 ? 0 : 1),
          "2025-09-01",
          "供教学与课程实验使用，借用前请检查配件，归还时上传设备照片。",
          Timestamp.from(Instant.now().minus(Duration.ofDays(45)))
        );
      }
      Instant n = Instant.now();
      String rid = id(),
        lid = id();
      db.update(
        "INSERT INTO reservation(id,reservation_no,equipment_id,user_id,lab_id,starts_at,ends_at,purpose,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
        rid,
        "YY-DEMO-001",
        eids.get(0),
        users.get("student"),
        labs.get(0),
        Timestamp.from(n.minusSeconds(7200)),
        Timestamp.from(n.plusSeconds(7200)),
        "电路课程实验",
        "FULFILLED",
        now()
      );
      db.update(
        "INSERT INTO loan_record(id,loan_no,reservation_id,equipment_id,user_id,lab_id,issued_by,issued_at,due_at,status) VALUES(?,?,?,?,?,?,?,?,?,?)",
        lid,
        "JY-DEMO-001",
        rid,
        eids.get(0),
        users.get("student"),
        labs.get(0),
        users.get("labstaff"),
        Timestamp.from(n.minusSeconds(7200)),
        Timestamp.from(n.plusSeconds(7200)),
        "BORROWED"
      );
      String tid = id();
      db.update(
        "INSERT INTO fault_ticket(id,ticket_no,equipment_id,lab_id,reporter_id,title,description,status,affects_use,assignee_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        tid,
        "GD-DEMO-001",
        eids.get(2),
        labs.get(0),
        users.get("student"),
        "信号输出幅度不稳定",
        "开机后输出波形幅度跳动，已检查连接线，等待进一步检修。",
        "ASSIGNED",
        true,
        users.get("technician"),
        now()
      );
      db.update(
        "INSERT INTO equipment_block VALUES(?,?,?,?,?,?,NULL)",
        id(),
        eids.get(2),
        "FAULT",
        tid,
        "信号输出异常",
        now()
      );
      db.event(
        "TICKET",
        tid,
        "已分派维修",
        users.get("labstaff"),
        "请检查输出接口与探头"
      );
      Path file = Path.of(
        env.getProperty(
          "SEED_DEMO_CREDENTIAL_FILE",
          ".runtime/local/demo-accounts.json"
        )
      );
      java.nio.file.Files.createDirectories(file.toAbsolutePath().getParent());
      java.nio.file.Files.writeString(
        file,
        db.encode(Map.of("accounts", users.keySet(), "password", demoPassword))
      );
      try {
        java.nio.file.Files.setPosixFilePermissions(
          file,
          java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
        );
      } catch (UnsupportedOperationException ignored) {}
    };
  }
}
