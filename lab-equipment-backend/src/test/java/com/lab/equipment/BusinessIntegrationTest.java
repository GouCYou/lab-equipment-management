package com.lab.equipment;

import static com.lab.equipment.Db.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BusinessIntegrationTest {

  @Autowired
  Db db;

  @Autowired
  Auth auth;

  @Autowired
  Business business;

  @Autowired
  Queries q;

  @Autowired
  Files files;

  @Autowired
  AiGateway ai;

  @Autowired
  MockMvc mvc;

  @Autowired
  ModuleUpdates moduleUpdates;

  @Test
  void conversationPinAndRenamePersistAndRespectOwnership() {
    String cid = id(),
      recent = id();
    db.update(
      "INSERT INTO conversation(id,user_id,title,created_at,updated_at) VALUES(?,?,?,?,?)",
      cid,
      student.id(),
      "旧标题",
      now(),
      Timestamp.from(Instant.now().minusSeconds(60))
    );
    db.update(
      "INSERT INTO conversation(id,user_id,title,created_at,updated_at) VALUES(?,?,?,?,?)",
      recent,
      student.id(),
      "最近对话",
      now(),
      now()
    );
    assertEquals(recent, str(ai.conversations(student).getFirst(), "id"));
    ai.updateConversation(
      student,
      cid,
      Map.of("pinned", true, "title", "新标题")
    );
    assertEquals(cid, str(ai.conversations(student).getFirst(), "id"));
    assertEquals("新标题", str(ai.conversation(student, cid), "title"));
    assertNotNull(ai.conversation(student, cid).get("pinnedAt"));
    assertThrows(ApiError.class, () ->
      ai.updateConversation(other, cid, Map.of("title", "越权"))
    );
    assertThrows(ApiError.class, () ->
      ai.updateConversation(student, cid, Map.of("title", "  "))
    );
    ai.updateConversation(student, cid, Map.of("pinned", false));
    assertNull(ai.conversation(student, cid).get("pinnedAt"));
    assertEquals(recent, str(ai.conversations(student).getFirst(), "id"));
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void editingBranchesAtSelectedMessageAndRetryKeepsOneUserTurn() {
    String cid = id(),
      first = id(),
      second = id(),
      rid = id();
    db.update(
      "INSERT INTO conversation(id,user_id,title,created_at,updated_at) VALUES(?,?,?,?,?)",
      cid,
      student.id(),
      "原对话",
      now(),
      now()
    );
    db.update(
      "INSERT INTO chat_message(id,conversation_id,role,content,created_at) VALUES(?,?,?,?,?)",
      first,
      cid,
      "user",
      "前文",
      Timestamp.from(Instant.now().minusSeconds(5))
    );
    db.update(
      "INSERT INTO chat_message(id,conversation_id,role,content,created_at) VALUES(?,?,?,?,?)",
      second,
      cid,
      "user",
      "旧问题",
      now()
    );
    var edited = ai.send(
      student,
      Map.of(
        "conversationId",
        cid,
        "editMessageId",
        second,
        "text",
        "修改后的问题",
        "photoIds",
        List.of()
      )
    );
    String branch = str(edited, "conversationId"),
      run = str(edited, "runId");
    assertNotEquals(cid, branch);
    assertEquals(
      2,
      db.count("SELECT COUNT(*) FROM chat_message WHERE conversation_id=?", cid)
    );
    var rows = db.rows(
      "SELECT content FROM chat_message WHERE conversation_id=? ORDER BY created_at,id",
      branch
    );
    assertEquals(
      List.of("前文", "修改后的问题"),
      rows
        .stream()
        .map(m -> str(m, "content"))
        .toList()
    );
    assertThrows(ApiError.class, () ->
      ai.send(
        other,
        Map.of(
          "conversationId",
          cid,
          "editMessageId",
          second,
          "text",
          "越权修改"
        )
      )
    );
    db.update(
      "UPDATE ai_run SET status='FAILED',error='upstream 429' WHERE id=?",
      run
    );
    assertThrows(ApiError.class, () -> ai.retry(other, run));
    var retry = ai.retry(student, run);
    assertEquals(run, str(retry, "runId"));
    assertEquals("QUEUED", str(ai.run(student, run), "status"));
    assertEquals(
      2,
      db.count(
        "SELECT COUNT(*) FROM chat_message WHERE conversation_id=?",
        branch
      )
    );
    assertThrows(ApiError.class, () -> ai.retry(student, run));
  }

  @Test
  void moduleUpdatesAreScopedAndAcknowledgementDoesNotHideNewChanges() {
    moduleUpdates.list(student);
    moduleUpdates.list(other);
    var reservation = reservation(student);
    var changed = db.object(
      db.encode(moduleUpdates.list(student).get("reservations"))
    );
    assertTrue(bool(changed.get("unread")));
    assertFalse(
      bool(
        db
          .object(db.encode(moduleUpdates.list(other).get("reservations")))
          .get("unread")
      )
    );
    String first = str(changed, "fingerprint");
    moduleUpdates.read(student, "reservations", Map.of("fingerprint", first));
    assertFalse(
      bool(
        db
          .object(db.encode(moduleUpdates.list(student).get("reservations")))
          .get("unread")
      )
    );
    business.reservationAction(
      staff,
      str(reservation, "id"),
      "approve",
      Map.of()
    );
    moduleUpdates.read(student, "reservations", Map.of("fingerprint", first));
    var newer = db.object(
      db.encode(moduleUpdates.list(student).get("reservations"))
    );
    assertTrue(bool(newer.get("unread")));
    moduleUpdates.read(
      student,
      "reservations",
      Map.of("fingerprint", str(newer, "fingerprint"))
    );
    assertFalse(
      bool(
        db
          .object(db.encode(moduleUpdates.list(student).get("reservations")))
          .get("unread")
      )
    );
    assertThrows(ApiError.class, () ->
      moduleUpdates.read(student, "admin", Map.of("fingerprint", first))
    );
  }

  @Test
  void committedAiOperationSurvivesModelSummaryFailure() {
    String cid = id(),
      rid = id();
    db.update(
      "INSERT INTO conversation(id,user_id,title,created_at,updated_at) VALUES(?,?,?,?,?)",
      cid,
      student.id(),
      "借用回执",
      now(),
      now()
    );
    db.update(
      "INSERT INTO ai_run(id,conversation_id,user_id,status,mode,query,photo_ids,context_json,started_at) VALUES(?,?,?,?,?,?,?,?,?)",
      rid,
      cid,
      student.id(),
      "RUNNING",
      "real",
      "我要借用设备",
      "[]",
      "{}",
      now()
    );
    business.reserve(student, request(), rid + ":reserve:" + equipment);
    assertTrue(ai.completeBusinessReceipt(rid, "模型总结超时"));
    var messages = (List<?>) ai.conversation(student, cid).get("messages");
    assertTrue(db.encode(messages).contains("预约申请已成功提交"));
    assertTrue(db.encode(messages).contains("系统业务回执"));
    assertEquals(
      "SUCCEEDED",
      db.one("SELECT status FROM ai_run WHERE id=?", rid).get("status")
    );
    assertTrue(ai.completeBusinessReceipt(rid, "重复收尾"));
    assertEquals(
      1,
      db.count(
        "SELECT COUNT(*) FROM chat_message WHERE run_id=? AND role='assistant'",
        rid
      )
    );
  }

  User student, other, staff, tech;
  String lab, equipment;
  Instant start, end;

  @BeforeEach
  void fixture() {
    String database = db.jdbc.queryForObject("SELECT DATABASE()", String.class);
    assertTrue(
      database != null && database.startsWith("lab_equipment_test"),
      "Integration tests require isolated lab_equipment_test database"
    );
    student = user("STUDENT");
    other = user("STUDENT");
    staff = user("LAB_STAFF");
    tech = user("TECHNICIAN");
    lab = id();
    db.update(
      "INSERT INTO laboratory(id,name,location,open_start,open_end,created_at) VALUES(?,?,?,0,24,?)",
      lab,
      "测试实验室" + lab,
      "测试楼",
      now()
    );
    db.update("INSERT INTO lab_staff_scope VALUES(?,?)", staff.id(), lab);
    db.update(
      "INSERT INTO lab_open_rule VALUES(?,?,0,24,?,NULL)",
      id(),
      lab,
      Timestamp.from(Instant.now().minus(Duration.ofDays(90)))
    );
    String category = id();
    db.update(
      "INSERT INTO equipment_category VALUES(?,?)",
      category,
      "测试分类" + category
    );
    equipment = id();
    db.update(
      "INSERT INTO equipment(id,asset_no,name,model,category_id,lab_id,created_at) VALUES(?,?,?,?,?,?,?)",
      equipment,
      "TEST-" + equipment,
      "示波器",
      "MODEL-X",
      category,
      lab,
      Timestamp.from(Instant.now().minus(Duration.ofDays(30)))
    );
    start = Instant.now().plusSeconds(60);
    end = start.plusSeconds(1800);
    Assumptions.assumeTrue(
      start
        .atZone(ZoneId.of("Asia/Shanghai"))
        .toLocalDate()
        .equals(end.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate())
    );
  }

  User user(String role) {
    String id = id();
    db.update(
      "INSERT INTO sys_user(id,username,password_hash,name,role,created_at) VALUES(?,?,?,?,?,?)",
      id,
      "test-" + id,
      auth.passwords.encode("TestPass123!"),
      role,
      role,
      now()
    );
    db.update("INSERT INTO sys_user_role VALUES(?,?)", id, role);
    return auth.user(id);
  }

  Map<String, Object> request() {
    return new LinkedHashMap<>(
      Map.of(
        "equipmentId",
        equipment,
        "startsAt",
        start.toString(),
        "endsAt",
        end.toString(),
        "purpose",
        "并发及权限验证"
      )
    );
  }

  Map<String, Object> reservation(User who) {
    return business.reserve(who, request(), id());
  }

  Map<String, Object> loan() {
    var r = reservation(student);
    business.reservationAction(staff, str(r, "id"), "approve", Map.of());
    return business.issue(staff, str(r, "id"), id());
  }

  String image(User u) throws Exception {
    var output = new ByteArrayOutputStream();
    ImageIO.write(
      new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB),
      "png",
      output
    );
    return str(
      files.upload(
        u,
        new MockMultipartFile(
          "file",
          "设备归还.png",
          "image/png",
          output.toByteArray()
        )
      ),
      "id"
    );
  }

  @Test
  void studentIsolationAndLabScope() {
    var r = reservation(student);
    assertEquals(0, q.reservations(other).size());
    assertThrows(ApiError.class, () -> q.reservation(other, str(r, "id")));
    User outsider = user("LAB_STAFF");
    assertThrows(ApiError.class, () ->
      business.reservationAction(outsider, str(r, "id"), "approve", Map.of())
    );
    assertThrows(ApiError.class, () -> q.statistics(student, 30, ""));
  }

  @Test
  void overlappingApprovalsSerialize() throws Exception {
    var a = reservation(student);
    var b = reservation(other);
    var barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<Boolean>> results = new ArrayList<>();
      for (var r : List.of(a, b))
        results.add(
          pool.submit(() -> {
            barrier.await();
            try {
              business.reservationAction(
                staff,
                str(r, "id"),
                "approve",
                Map.of()
              );
              return true;
            } catch (ApiError e) {
              return false;
            }
          })
        );
      int successes = 0;
      for (var f : results) if (f.get(15, TimeUnit.SECONDS)) successes++;
      assertEquals(1, successes);
    }
    assertEquals(
      1,
      db.count(
        "SELECT COUNT(*) FROM reservation WHERE equipment_id=? AND status='APPROVED'",
        equipment
      )
    );
  }

  @Test
  void adjacentSlotsDoNotOverlap() {
    var r = reservation(student);
    business.reservationAction(staff, str(r, "id"), "approve", Map.of());
    var second = request();
    second.put("startsAt", end.toString());
    second.put("endsAt", end.plusSeconds(600).toString());
    var r2 = business.reserve(other, second, id());
    business.reservationAction(staff, str(r2, "id"), "approve", Map.of());
    assertEquals(
      2,
      db.count(
        "SELECT COUNT(*) FROM reservation WHERE equipment_id=? AND status='APPROVED'",
        equipment
      )
    );
  }

  @Test
  void duplicateIssueAndRequestIdempotency() {
    String key = id();
    var a = business.reserve(student, request(), key);
    var b = business.reserve(student, request(), key);
    assertEquals(a.get("id"), b.get("id"));
    var altered = request();
    altered.put("purpose", "不同内容");
    assertThrows(ApiError.class, () -> business.reserve(student, altered, key));
    business.reservationAction(staff, str(a, "id"), "approve", Map.of());
    String ikey = id();
    var l = business.issue(staff, str(a, "id"), ikey);
    assertEquals(
      l.get("id"),
      business.issue(staff, str(a, "id"), ikey).get("id")
    );
    assertThrows(ApiError.class, () ->
      business.issue(staff, str(a, "id"), id())
    );
    assertEquals(
      1,
      db.count(
        "SELECT COUNT(*) FROM loan_record WHERE equipment_id=? AND returned_at IS NULL",
        equipment
      )
    );
  }

  @Test
  void photoReturnIsImmediateAndOwned() throws Exception {
    var l = loan();
    String lid = str(l, "id"),
      photo = image(student);
    assertThrows(ApiError.class, () ->
      business.returnLoan(other, lid, Map.of("photoIds", List.of(photo)), id())
    );
    assertThrows(ApiError.class, () ->
      business.returnLoan(student, lid, Map.of("photoIds", List.of()), id())
    );
    String key = id();
    var body = Map.<String, Object>of(
      "photoIds",
      List.of(photo),
      "note",
      "已放回设备柜"
    );
    var result = business.returnLoan(student, lid, body, key);
    assertEquals("RETURNED", result.get("status"));
    assertNotNull(result.get("returnedAt"));
    assertEquals(
      result.get("id"),
      business.returnLoan(student, lid, body, key).get("id")
    );
    assertEquals(
      0,
      db.count(
        "SELECT COUNT(*) FROM loan_record WHERE equipment_id=? AND returned_at IS NULL",
        equipment
      )
    );
    assertFalse(files.accessible(other, photo));
    assertTrue(files.accessible(staff, photo));
  }

  Map<String, Object> ticket() {
    return business.createTicket(
      student,
      Map.of(
        "equipmentId",
        equipment,
        "title",
        "显示异常",
        "description",
        "开机没有波形，请核实",
        "photoIds",
        List.of()
      )
    );
  }

  void closeTicket(Map<String, Object> t) {
    String id = str(t, "id");
    business.ticketAction(staff, id, "assign", Map.of("assigneeId", tech.id()));
    business.ticketAction(tech, id, "accept", Map.of());
    business.ticketAction(tech, id, "start-repair", Map.of());
    business.ticketAction(
      tech,
      id,
      "submit-acceptance",
      Map.of("resolution", "更换接头并检查通过", "laborMinutes", 10)
    );
    assertFalse(q.ticket(tech, id).containsKey("costAmount"));
    assertThrows(ApiError.class, () ->
      business.ticketAction(
        tech,
        id,
        "acceptance",
        Map.of("passed", true, "note", "通过")
      )
    );
    business.ticketAction(
      staff,
      id,
      "acceptance",
      Map.of("passed", true, "note", "复测正常")
    );
  }

  @Test
  void multipleFaultsAndManualDisableRemainIndependent() {
    var a = ticket();
    var b = ticket();
    business.ticketAction(
      staff,
      str(a, "id"),
      "verify",
      Map.of("affectsUse", true)
    );
    business.ticketAction(
      staff,
      str(b, "id"),
      "verify",
      Map.of("affectsUse", true)
    );
    assertThrows(ApiError.class, () -> reservation(other));
    closeTicket(a);
    assertEquals(
      "MAINTENANCE",
      q.equipment(staff, equipment).get("operationalStatus")
    );
    business.toggleEquipment(
      staff,
      equipment,
      Map.of("enabled", false, "reason", "实验室暂停开放")
    );
    closeTicket(b);
    assertEquals(
      "DISABLED",
      q.equipment(staff, equipment).get("operationalStatus")
    );
    business.toggleEquipment(
      staff,
      equipment,
      Map.of("enabled", true, "reason", "重新开放")
    );
    assertEquals(
      "NORMAL",
      q.equipment(staff, equipment).get("operationalStatus")
    );
  }

  @Test
  void verifiedFaultInvalidatesUnissuedReservation() {
    var r = reservation(student);
    business.reservationAction(staff, str(r, "id"), "approve", Map.of());
    var t = ticket();
    business.ticketAction(
      staff,
      str(t, "id"),
      "verify",
      Map.of("affectsUse", true)
    );
    assertEquals(
      "INVALIDATED",
      q.reservation(student, str(r, "id")).get("status")
    );
    assertThrows(ApiError.class, () ->
      business.issue(staff, str(r, "id"), id())
    );
  }

  @Test
  void invalidTicketTransitionAndTechnicianScope() {
    var t = ticket();
    assertThrows(ApiError.class, () ->
      business.ticketAction(tech, str(t, "id"), "start-repair", Map.of())
    );
    business.ticketAction(
      staff,
      str(t, "id"),
      "verify",
      Map.of("affectsUse", false)
    );
    business.ticketAction(
      staff,
      str(t, "id"),
      "assign",
      Map.of("assigneeId", tech.id())
    );
    assertEquals(str(t, "id"), q.ticket(tech, str(t, "id")).get("id"));
    assertThrows(ApiError.class, () ->
      q.ticket(user("TECHNICIAN"), str(t, "id"))
    );
  }

  @Test
  void revokedAccountInvalidatesExistingSession() throws Exception {
    var username = db
      .one("SELECT username FROM sys_user WHERE id=?", student.id())
      .get("username");
    var response = mvc
      .perform(
        post("/api/auth/login")
          .contentType("application/json")
          .content(
            db.encode(Map.of("username", username, "password", "TestPass123!"))
          )
      )
      .andExpect(status().isOk())
      .andReturn();
    String token = str(
      db.object(response.getResponse().getContentAsString()),
      "token"
    );
    mvc
      .perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
      .andExpect(status().isOk());
    db.update(
      "UPDATE sys_user SET enabled=FALSE,auth_version=auth_version+1 WHERE id=?",
      student.id()
    );
    mvc
      .perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
      .andExpect(status().isUnauthorized());
  }

  @Test
  void unavailableIntervalsAreUnioned() {
    List<long[]> base = new ArrayList<>();
    base.add(new long[] { 0, 100 });
    base = Queries.subtract(base, 20, 60);
    base = Queries.subtract(base, 40, 80);
    assertEquals(
      40,
      base
        .stream()
        .mapToLong(r -> r[1] - r[0])
        .sum()
    );
    assertEquals(0, Queries.subtract(base, -10, 110).size());
  }

  @Test
  void aiToolsRejectForgedGrantsAndEnforceBudget() {
    String cid = id(),
      rid = id(),
      grant = "fixture-grant";
    db.update(
      "INSERT INTO conversation(id,user_id,title,created_at,updated_at) VALUES(?,?,?,?,?)",
      cid,
      student.id(),
      "测试",
      now(),
      now()
    );
    db.update(
      "INSERT INTO ai_run(id,conversation_id,user_id,status,mode,query,photo_ids,context_json,grant_hash,started_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
      rid,
      cid,
      student.id(),
      "RUNNING",
      "demo",
      "查询",
      "[]",
      "{}",
      hash(grant),
      now()
    );
    assertThrows(ApiError.class, () ->
      ai.tool(rid, "forged", "query_loans", Map.of())
    );
    assertThrows(ApiError.class, () ->
      ai.tool(rid, grant, "get_statistics", Map.of())
    );
    for (int i = 0; i < 5; i++) ai.tool(rid, grant, "query_loans", Map.of());
    assertThrows(ApiError.class, () ->
      ai.tool(rid, grant, "query_loans", Map.of())
    );
  }

  @Test
  void historicalAssistantResultHidesAfterLabPermissionRemoval() {
    String cid = id(),
      rid = id();
    db.update(
      "INSERT INTO conversation(id,user_id,title,created_at,updated_at) VALUES(?,?,?,?,?)",
      cid,
      staff.id(),
      "实验室总结",
      now(),
      now()
    );
    db.update(
      "INSERT INTO ai_run(id,conversation_id,user_id,status,mode,query,photo_ids,context_json,started_at) VALUES(?,?,?,?,?,?,?,?,?)",
      rid,
      cid,
      staff.id(),
      "SUCCEEDED",
      "demo",
      "总结",
      "[]",
      db.encode(Map.of("_roles", staff.roles(), "_labIds", List.of(lab))),
      now()
    );
    db.update(
      "INSERT INTO chat_message(id,conversation_id,role,content,cards,citations,run_id,created_at) VALUES(?,?,?,?,?,?,?,?)",
      id(),
      cid,
      "assistant",
      "实验室内部结果",
      "[]",
      "[]",
      rid,
      now()
    );
    db.update("DELETE FROM lab_staff_scope WHERE user_id=?", staff.id());
    var conversation = ai.conversation(auth.user(staff.id()), cid);
    var messages = (List<?>) conversation.get("messages");
    assertTrue(db.encode(messages).contains("权限范围已变更"));
    assertFalse(db.encode(messages).contains("实验室内部结果"));
  }

  @Test
  void reportCreationIsIdempotent() {
    String key = id();
    var body = Map.<String, Object>of(
      "equipmentId",
      equipment,
      "title",
      "接口异常",
      "description",
      "请检查输出端口",
      "photoIds",
      List.of()
    );
    var first = business.createTicket(student, body, key);
    assertEquals(
      first.get("id"),
      business.createTicket(student, body, key).get("id")
    );
    assertEquals(
      1,
      db.count(
        "SELECT COUNT(*) FROM fault_ticket WHERE equipment_id=?",
        equipment
      )
    );
  }

  @Test
  void consultingAndNegativeInstructionsCannotTriggerAiWrites() {
    assertThrows(ApiError.class, () -> ai.writeIntent("不要归还这台设备"));
    assertThrows(ApiError.class, () -> ai.writeIntent("如何归还设备"));
    assertThrows(ApiError.class, () ->
      ai.writeIntent("取消预约，不要帮我借用")
    );
    assertDoesNotThrow(() -> ai.writeIntent("归还设备 LAB-0001"));
  }

  @Test
  void statisticsRespectScopeAndExposeTraceableDenominator() {
    var l = loan();
    db.update(
      "UPDATE loan_record SET issued_at=? WHERE id=?",
      Timestamp.from(Instant.now().minusSeconds(3600)),
      l.get("id")
    );
    var stats = q.statistics(staff, 7, lab);
    assertEquals(1, stats.get("equipmentCount"));
    assertEquals(1, stats.get("loanCount"));
    assertEquals(1L, stats.get("currentBorrowed"));
    assertTrue(((Number) stats.get("usedHours")).doubleValue() >= .99);
    assertTrue(((Number) stats.get("openHours")).doubleValue() > 0);
    assertTrue(((Number) stats.get("utilization")).doubleValue() <= 100);
    assertThrows(ApiError.class, () -> q.statistics(user("LAB_STAFF"), 7, lab));
  }
}
