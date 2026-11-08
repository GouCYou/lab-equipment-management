package com.lab.equipment;

import static com.lab.equipment.Db.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class Api {

  final Auth auth;
  final Db db;
  final Queries q;
  final Business business;
  final Admin admin;
  final Files files;
  final AiGateway ai;
  final Knowledge knowledge;
  final ModuleUpdates moduleUpdates;

  Api(
    Auth auth,
    Db db,
    Queries q,
    Business business,
    Admin admin,
    Files files,
    AiGateway ai,
    Knowledge knowledge,
    ModuleUpdates moduleUpdates
  ) {
    this.auth = auth;
    this.db = db;
    this.q = q;
    this.business = business;
    this.admin = admin;
    this.files = files;
    this.ai = ai;
    this.knowledge = knowledge;
    this.moduleUpdates = moduleUpdates;
  }

  @GetMapping("/health")
  Object health() {
    db.count("SELECT 1");
    return Map.of(
      "status",
      "UP",
      "application",
      "lab-equipment-management",
      "testEnvironment",
      String.valueOf(
        db.jdbc.queryForObject("SELECT DATABASE()", String.class)
      ).startsWith("lab_equipment_test")
    );
  }

  @PostMapping("/auth/login")
  Object login(@RequestBody Map<String, Object> b, HttpServletRequest r) {
    return auth.login(b, r.getRemoteAddr());
  }

  @PostMapping("/auth/logout")
  Object logout(@RequestHeader("Authorization") String token) {
    auth.logout(token.substring(7));
    return Map.of("ok", true);
  }

  @GetMapping("/auth/me")
  Object me() {
    return auth.profile(auth.current());
  }

  @PutMapping("/auth/profile")
  Object profile(@RequestBody Map<String, Object> b) {
    User u = auth.current();
    db.update(
      "UPDATE sys_user SET name=? WHERE id=?",
      required(b, "name", 80),
      u.id()
    );
    return auth.profile(auth.user(u.id()));
  }

  @PutMapping("/auth/password")
  Object password(@RequestBody Map<String, Object> b) {
    admin.changePassword(auth.current(), b);
    return Map.of("ok", true);
  }

  @GetMapping("/dashboard")
  Object dashboard() {
    return q.dashboard(auth.current());
  }

  @GetMapping("/laboratories")
  Object labs() {
    return q.labs();
  }

  @GetMapping("/categories")
  Object categories() {
    return q.categories();
  }

  @GetMapping("/technicians")
  Object technicians() {
    return q.technicians(auth.current());
  }

  @GetMapping("/equipment")
  Object equipment(
    @RequestParam(defaultValue = "") String search,
    @RequestParam(defaultValue = "") String labId,
    @RequestParam(defaultValue = "") String categoryId,
    @RequestParam(defaultValue = "false") boolean availableOnly
  ) {
    return q.equipment(
      auth.current(),
      search,
      labId,
      categoryId,
      availableOnly
    );
  }

  @GetMapping("/equipment/{id}")
  Object equipment(@PathVariable String id) {
    return q.equipment(auth.current(), id);
  }

  @GetMapping("/equipment/{id}/availability")
  Object availability(
    @PathVariable String id,
    @RequestParam String startsAt,
    @RequestParam String endsAt
  ) {
    var e = db.one("SELECT * FROM equipment WHERE id=?", id);
    try {
      business.usable(e);
      var b = Map.<String, Object>of("startsAt", startsAt, "endsAt", endsAt);
      business.interval(
        e,
        business.time(b, "startsAt"),
        business.time(b, "endsAt"),
        ""
      );
      return Map.of(
        "available",
        true,
        "reason",
        "当前时段可申请，最终以审核和发放为准"
      );
    } catch (ApiError err) {
      return Map.of("available", false, "reason", err.getMessage());
    }
  }

  @PostMapping("/equipment")
  Object createEquipment(@RequestBody Map<String, Object> b) {
    return business.saveEquipment(auth.current(), null, b);
  }

  @PutMapping("/equipment/{id}")
  Object updateEquipment(
    @PathVariable String id,
    @RequestBody Map<String, Object> b
  ) {
    return business.saveEquipment(auth.current(), id, b);
  }

  @PostMapping("/equipment/{id}/toggle")
  Object toggle(@PathVariable String id, @RequestBody Map<String, Object> b) {
    return business.toggleEquipment(auth.current(), id, b);
  }

  @GetMapping("/reservations")
  Object reservations() {
    return q.reservations(auth.current());
  }

  @PostMapping("/reservations")
  Object reserve(
    @RequestBody Map<String, Object> b,
    @RequestHeader(value = "Idempotency-Key", required = false) String key
  ) {
    return business.reserve(auth.current(), b, key);
  }

  @PostMapping("/reservations/{id}/issue")
  Object issue(
    @PathVariable String id,
    @RequestHeader(value = "Idempotency-Key", required = false) String key
  ) {
    return business.issue(auth.current(), id, key);
  }

  @PostMapping("/reservations/{id}/{action}")
  Object reservationAction(
    @PathVariable String id,
    @PathVariable String action,
    @RequestBody(required = false) Map<String, Object> b
  ) {
    return business.reservationAction(
      auth.current(),
      id,
      action,
      b == null ? Map.of() : b
    );
  }

  @GetMapping("/loans")
  Object loans(@RequestParam(defaultValue = "false") boolean activeOnly) {
    return q.loans(auth.current(), activeOnly);
  }

  @GetMapping("/loans/{id}")
  Object loan(@PathVariable String id) {
    var l = q.loan(auth.current(), id);
    l.put("events", db.events("LOAN", id));
    return l;
  }

  @PostMapping("/loans/{id}/return")
  Object returnLoan(
    @PathVariable String id,
    @RequestBody Map<String, Object> b,
    @RequestHeader(value = "Idempotency-Key", required = false) String key
  ) {
    return business.returnLoan(auth.current(), id, b, key);
  }

  @PostMapping("/loans/{id}/check")
  Object check(@PathVariable String id) {
    return business.checkReturn(auth.current(), id);
  }

  @GetMapping("/tickets")
  Object tickets() {
    return q.tickets(auth.current());
  }

  @GetMapping("/tickets/{id}")
  Object ticket(@PathVariable String id) {
    return q.ticket(auth.current(), id);
  }

  @PostMapping("/tickets")
  Object createTicket(
    @RequestBody Map<String, Object> b,
    @RequestHeader(value = "Idempotency-Key", required = false) String key
  ) {
    return business.createTicket(auth.current(), b, key);
  }

  @PostMapping("/tickets/{id}/{action}")
  Object ticketAction(
    @PathVariable String id,
    @PathVariable String action,
    @RequestBody(required = false) Map<String, Object> b
  ) {
    return business.ticketAction(
      auth.current(),
      id,
      action,
      b == null ? Map.of() : b
    );
  }

  @PostMapping("/files")
  Object upload(@RequestPart("file") MultipartFile file) {
    return files.upload(auth.current(), file);
  }

  @GetMapping("/files/{id}")
  ResponseEntity<byte[]> file(@PathVariable String id) {
    return files.download(auth.current(), id);
  }

  @GetMapping("/module-updates")
  Object moduleUpdates() {
    return moduleUpdates.list(auth.current());
  }

  @PostMapping("/module-updates/{module}/read")
  Object moduleRead(
    @PathVariable String module,
    @RequestBody Map<String, Object> body
  ) {
    return moduleUpdates.read(auth.current(), module, body);
  }

  @GetMapping("/notifications")
  Object notifications() {
    return q.notifications(auth.current());
  }

  @PostMapping("/notifications/read-all")
  Object readAll() {
    db.update(
      "UPDATE notification SET read_at=? WHERE user_id=? AND read_at IS NULL",
      now(),
      auth.current().id()
    );
    return Map.of("ok", true);
  }

  @PostMapping("/notifications/{id}/read")
  Object read(@PathVariable String id) {
    db.update(
      "UPDATE notification SET read_at=? WHERE id=? AND user_id=?",
      now(),
      id,
      auth.current().id()
    );
    return Map.of("ok", true);
  }

  @GetMapping("/statistics")
  Object stats(
    @RequestParam(defaultValue = "30") int days,
    @RequestParam(defaultValue = "") String labId
  ) {
    return q.statistics(auth.current(), days, labId);
  }

  @GetMapping("/knowledge")
  Object knowledge() {
    return knowledge.list(auth.current());
  }

  @GetMapping("/knowledge/{id}")
  Object doc(@PathVariable String id) {
    return knowledge.detail(auth.current(), id);
  }

  @PostMapping("/knowledge")
  Object saveDoc(@RequestBody Map<String, Object> b) {
    return knowledge.save(auth.current(), null, b);
  }

  @PostMapping("/knowledge/{id}/versions")
  Object version(@PathVariable String id, @RequestBody Map<String, Object> b) {
    return knowledge.save(auth.current(), id, b);
  }

  @PostMapping("/knowledge/search")
  Object search(@RequestBody Map<String, Object> b) {
    return knowledge.search(
      auth.current(),
      required(b, "query", 1000),
      str(b, "model"),
      str(b, "category")
    );
  }

  @PostMapping("/knowledge/{id}/{action}")
  Object docAction(
    @PathVariable String id,
    @PathVariable String action,
    @RequestBody Map<String, Object> b
  ) {
    return knowledge.action(auth.current(), id, action, b);
  }

  @GetMapping("/conversations")
  Object conversations() {
    return ai.conversations(auth.current());
  }

  @GetMapping("/conversations/{id}")
  Object conversation(@PathVariable String id) {
    return ai.conversation(auth.current(), id);
  }

  @PatchMapping("/conversations/{id}")
  Object updateConversation(
    @PathVariable String id,
    @RequestBody Map<String, Object> body
  ) {
    return ai.updateConversation(auth.current(), id, body);
  }

  @DeleteMapping("/conversations/{id}")
  Object deleteConversation(@PathVariable String id) {
    ai.deleteConversation(auth.current(), id);
    return Map.of("ok", true);
  }

  @PostMapping("/chat")
  Object chat(
    @RequestBody Map<String, Object> b,
    @RequestHeader(value = "Idempotency-Key", required = false) String key
  ) {
    return ai.send(auth.current(), b, key);
  }

  @PostMapping("/ai-runs/{id}/retry")
  Object retryRun(@PathVariable String id) {
    return ai.retry(auth.current(), id);
  }

  @GetMapping("/ai-runs/{id}")
  Object run(@PathVariable String id) {
    return ai.run(auth.current(), id);
  }

  @GetMapping("/admin/users")
  Object users() {
    return admin.users(auth.current());
  }

  @PostMapping("/admin/users")
  Object newUser(@RequestBody Map<String, Object> b) {
    return admin.saveUser(auth.current(), null, b);
  }

  @PutMapping("/admin/users/{id}")
  Object user(@PathVariable String id, @RequestBody Map<String, Object> b) {
    return admin.saveUser(auth.current(), id, b);
  }

  @PostMapping("/admin/laboratories")
  Object newLab(@RequestBody Map<String, Object> b) {
    return admin.saveLab(auth.current(), null, b);
  }

  @PutMapping("/admin/laboratories/{id}")
  Object lab(@PathVariable String id, @RequestBody Map<String, Object> b) {
    return admin.saveLab(auth.current(), id, b);
  }

  @PostMapping("/admin/categories")
  Object category(@RequestBody Map<String, Object> b) {
    auth.admin(auth.current());
    String id = id();
    db.update(
      "INSERT INTO equipment_category VALUES(?,?)",
      id,
      required(b, "name", 80)
    );
    return Map.of("id", id);
  }

  @GetMapping("/admin/roles")
  Object roles() {
    auth.admin(auth.current());
    return db.rows(
      "SELECT r.*,GROUP_CONCAT(p.permission_code) AS permissions FROM sys_role r LEFT JOIN sys_role_permission p ON p.role_code=r.code GROUP BY r.code,r.name"
    );
  }

  @GetMapping("/admin/audit-logs")
  Object audit() {
    auth.admin(auth.current());
    return db.rows(
      "SELECT a.*,u.name AS actor_name FROM audit_log a LEFT JOIN sys_user u ON u.id=a.actor_id ORDER BY a.created_at DESC LIMIT 300"
    );
  }

  @GetMapping("/admin/ai-settings")
  Object settings() {
    auth.admin(auth.current());
    return ai.settings(false);
  }

  @PutMapping("/admin/ai-settings")
  Object saveSettings(@RequestBody Map<String, Object> b) {
    return ai.saveSettings(auth.current(), b);
  }

  @PostMapping("/admin/ai-test")
  Object testModel() {
    auth.admin(auth.current());
    return ai.request(
      "/internal/test-model",
      Map.of("config", ai.settings(true))
    );
  }
}

@RestController
@RequestMapping("/internal")
class InternalApi {

  final AiGateway ai;

  InternalApi(AiGateway ai) {
    this.ai = ai;
  }

  @PostMapping("/tools/{tool}")
  Object tool(
    @RequestHeader(value = "X-Internal-Key", required = false) String key,
    @RequestHeader(value = "X-Run-Grant", required = false) String grant,
    @RequestHeader("X-Run-Id") String run,
    @PathVariable String tool,
    @RequestBody Map<String, Object> args
  ) {
    ai.internal(key);
    return ai.tool(run, grant, tool, args);
  }
}
