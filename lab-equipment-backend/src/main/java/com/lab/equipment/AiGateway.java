package com.lab.equipment;

import static com.lab.equipment.ApiError.require;
import static com.lab.equipment.Db.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AiGateway {

  final Db db;
  final Auth auth;
  final Queries q;
  final Business business;
  final Files files;
  final Environment env;

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.transaction.PlatformTransactionManager transactions;

  final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build();
  final ExecutorService executor = new ThreadPoolExecutor(
    4,
    4,
    0,
    TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(24),
    Thread.ofPlatform().name("lab-ai-", 0).factory(),
    new ThreadPoolExecutor.AbortPolicy()
  );

  AiGateway(
    Db db,
    Auth auth,
    Queries q,
    Business business,
    Files files,
    Environment env
  ) {
    this.db = db;
    this.auth = auth;
    this.q = q;
    this.business = business;
    this.files = files;
    this.env = env;
  }

  public void enqueue(Runnable task) {
    executor.submit(task);
  }

  void completeRun(
    Map<String, Object> run,
    String content,
    Object cards,
    Object citations,
    Object result
  ) {
    new org.springframework.transaction.support.TransactionTemplate(
      transactions
    ).executeWithoutResult(tx -> {
      var current = db.one(
        "SELECT status FROM ai_run WHERE id=? FOR UPDATE",
        run.get("id")
      );
      if (!Set.of("RUNNING", "QUEUED").contains(str(current, "status"))) return;
      db.update(
        "INSERT INTO chat_message(id,conversation_id,role,content,cards,citations,run_id,created_at) VALUES(?,?,?,?,?,?,?,?)",
        id(),
        run.get("conversationId"),
        "assistant",
        content,
        db.encode(cards),
        db.encode(citations),
        run.get("id"),
        now()
      );
      db.update(
        "UPDATE ai_run SET status='SUCCEEDED',result_json=?,error=NULL,finished_at=?,grant_hash=NULL WHERE id=?",
        db.encode(result),
        now(),
        run.get("id")
      );
    });
  }

  boolean completeBusinessReceipt(String rid, String error) {
    var run = db.one("SELECT * FROM ai_run WHERE id=?", rid);
    User user = auth.user(str(run, "userId"));
    var receipts = db.rows(
      "SELECT operation,response_json FROM idempotency_record WHERE user_id=? AND idempotency_key LIKE ? ORDER BY created_at",
      user.id(),
      rid + ":%"
    );
    if (receipts.isEmpty() || !evidenceAccessible(user, rid)) return false;
    List<Object> cards = new ArrayList<>();
    List<String> lines = new ArrayList<>();
    for (var receipt : receipts) {
      var body = db.object(str(receipt, "responseJson"));
      String operation = str(receipt, "operation");
      if (operation.equals("reserve")) {
        cards.add(
          Map.of(
            "type",
            "reservation",
            "data",
            q.reservation(user, str(body, "id"))
          )
        );
        lines.add("预约申请已成功提交。");
      }
      if (operation.startsWith("return:")) {
        cards.add(
          Map.of("type", "loan", "data", q.loan(user, str(body, "id")))
        );
        lines.add("设备已归还，照片已保存。");
      }
      if (operation.equals("report")) {
        cards.add(
          Map.of("type", "ticket", "data", q.ticket(user, str(body, "id")))
        );
        lines.add("故障报修已成功提交。");
      }
    }
    if (cards.isEmpty()) return false;
    String content =
      String.join("\n\n", lines) +
      "\n\n以上为系统业务回执。AI 后续总结未完成，请以卡片和业务记录为准。";
    completeRun(
      run,
      content,
      cards,
      List.of(),
      Map.of(
        "text",
        content,
        "cards",
        cards,
        "citations",
        List.of(),
        "mode",
        run.get("mode"),
        "responseSource",
        "business_receipt",
        "warning",
        error
      )
    );
    return true;
  }

  boolean evidenceAccessible(User u, String runId) {
    for (var row : db.rows(
      "SELECT object_type,object_id FROM ai_access WHERE run_id=?",
      runId
    )) {
      try {
        switch (str(row, "objectType")) {
          case "TICKET" -> q.ticket(u, str(row, "objectId"));
          case "LOAN" -> q.loan(u, str(row, "objectId"));
          case "CHUNK" -> knowledge().historicalCitation(
            u,
            str(row, "objectId")
          );
        }
      } catch (ApiError e) {
        return false;
      }
    }
    return true;
  }

  void access(String runId, String type, String object) {
    db.update(
      "INSERT IGNORE INTO ai_access VALUES(?,?,?)",
      runId,
      type,
      object
    );
  }

  void writeIntent(String query) {
    require(
      !query.matches(
        "(?is).*(不要|别|不许|禁止|不要自动|不想|暂不|取消|do not|don't).*(归还|预约|借用|报修|return|reserve|report).*"
      ),
      "本轮包含取消或禁止操作的要求，未执行"
    );
    require(
      !query.matches(
        "(?s).*(怎么|如何|能否|是否可以).*(归还|预约|借用|报修).*"
      ),
      "当前是操作咨询，请通过卡片或明确操作请求办理"
    );
  }

  void internal(String key) {
    String expected = env.getProperty("AI_INTERNAL_KEY", "");
    if (
      expected.isBlank() ||
      key == null ||
      !MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        key.getBytes(StandardCharsets.UTF_8)
      )
    ) throw new ApiError(401, "INTERNAL_AUTH", "内部授权无效");
  }

  Map<String, Object> request(String path, Object payload) {
    try {
      var r = http.send(
        HttpRequest.newBuilder(
          URI.create(
            env.getProperty("AI_BASE_URL", "http://127.0.0.1:18100") + path
          )
        )
          .timeout(Duration.ofSeconds(130))
          .header("Content-Type", "application/json")
          .header("X-Internal-Key", env.getProperty("AI_INTERNAL_KEY", ""))
          .POST(HttpRequest.BodyPublishers.ofString(db.encode(payload)))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      );
      var body = db.object(r.body());
      if (r.statusCode() >= 400) throw new ApiError(
        503,
        "AI_UNAVAILABLE",
        str(body, "detail").isEmpty() ? "智能服务暂不可用" : str(body, "detail")
      );
      return body;
    } catch (ApiError e) {
      throw e;
    } catch (Exception e) {
      throw new ApiError(
        503,
        "AI_UNAVAILABLE",
        "智能服务连接失败或超时；基础业务仍可正常使用"
      );
    }
  }

  String encrypt(String value) {
    try {
      byte[] iv = new byte[12];
      new SecureRandom().nextBytes(iv);
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, secret(), new GCMParameterSpec(128, iv));
      byte[] data = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
      return (
        Base64.getEncoder().encodeToString(iv) +
        "." +
        Base64.getEncoder().encodeToString(data)
      );
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  String decrypt(String value) {
    try {
      String[] parts = value.split("\\.");
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
        Cipher.DECRYPT_MODE,
        secret(),
        new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0]))
      );
      return new String(
        c.doFinal(Base64.getDecoder().decode(parts[1])),
        StandardCharsets.UTF_8
      );
    } catch (Exception e) {
      throw new ApiError(
        503,
        "SETTINGS_KEY_CHANGED",
        "模型配置解密失败，请重新保存密钥"
      );
    }
  }

  SecretKeySpec secret() throws Exception {
    return new SecretKeySpec(
      MessageDigest.getInstance("SHA-256").digest(
        env.getProperty("AI_INTERNAL_KEY", "").getBytes(StandardCharsets.UTF_8)
      ),
      "AES"
    );
  }

  Map<String, Object> settings(boolean secrets) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (String k : List.of(
      "GENERATION_BASE_URL",
      "GENERATION_API_KEY",
      "GENERATION_MODEL"
    )) {
      var row = db.rows(
        "SELECT value_text FROM system_setting WHERE setting_key=?",
        k
      );
      String v = row.isEmpty()
        ? env.getProperty(k, "")
        : str(row.getFirst(), "valueText");
      if (!row.isEmpty() && k.endsWith("API_KEY") && !v.isEmpty()) v = decrypt(
        v
      );
      if (secrets || !k.endsWith("API_KEY")) out.put(k, v);
      else out.put(k + "_CONFIGURED", !v.isBlank());
    }
    out.put("AI_MODE", "real");
    return out;
  }

  @Transactional
  public Map<String, Object> saveSettings(User u, Map<String, Object> b) {
    auth.admin(u);
    for (String k : List.of(
      "GENERATION_BASE_URL",
      "GENERATION_API_KEY",
      "GENERATION_MODEL"
    )) {
      if (!b.containsKey(k)) continue;
      String v = str(b, k).trim();
      if (k.endsWith("API_KEY") && v.isEmpty()) continue;
      require(v.length() <= 2000, "配置内容过长");
      if (k.endsWith("BASE_URL") && !v.isEmpty()) {
        URI uri = URI.create(v);
        require(
          Set.of("http", "https").contains(uri.getScheme()) &&
            uri.getHost() != null &&
            uri.getUserInfo() == null,
          "模型地址必须是有效的 http/https URL"
        );
        v = v.replaceAll("/+$", "");
      }
      if (k.endsWith("API_KEY")) v = encrypt(v);
      db.update(
        "INSERT INTO system_setting VALUES(?,?,?) ON DUPLICATE KEY UPDATE value_text=VALUES(value_text),updated_at=VALUES(updated_at)",
        k,
        v,
        now()
      );
    }
    db.audit(
      u.id(),
      "SAVE_AI_SETTINGS",
      "models",
      "模型设置已更新，密钥不记录"
    );
    return settings(false);
  }

  List<Map<String, Object>> conversations(User u) {
    return db.rows(
      "SELECT * FROM conversation WHERE user_id=? ORDER BY pinned_at DESC,updated_at DESC LIMIT 100",
      u.id()
    );
  }

  @Transactional
  public Map<String, Object> updateConversation(
    User u,
    String cid,
    Map<String, Object> body
  ) {
    db.one(
      "SELECT id FROM conversation WHERE id=? AND user_id=? FOR UPDATE",
      cid,
      u.id()
    );
    require(
      body.containsKey("title") || body.containsKey("pinned"),
      "请选择操作"
    );
    if (body.containsKey("title")) {
      String title = str(body, "title").trim();
      require(
        !title.isEmpty() && title.length() <= 100,
        "标题需为 1 至 100 个字"
      );
      db.update("UPDATE conversation SET title=? WHERE id=?", title, cid);
    }
    if (body.containsKey("pinned")) {
      require(body.get("pinned") instanceof Boolean, "置顶状态无效");
      db.update(
        "UPDATE conversation SET pinned_at=? WHERE id=?",
        bool(body.get("pinned")) ? now() : null,
        cid
      );
    }
    return db.one("SELECT * FROM conversation WHERE id=?", cid);
  }

  Map<String, Object> conversation(User u, String id) {
    var c = db.one(
      "SELECT * FROM conversation WHERE id=? AND user_id=?",
      id,
      u.id()
    );
    var messages = db.rows(
      "SELECT m.*,r.context_json,r.mode FROM chat_message m LEFT JOIN ai_run r ON r.id=m.run_id WHERE m.conversation_id=? ORDER BY m.created_at,m.id",
      id
    );
    var currentLabs = strings(auth.profile(u).get("labIds"));
    for (var m : messages) {
      if (!str(m, "role").equals("assistant")) continue;
      var context = db.object(db.encode(m.get("contextJson")));
      if (
        !u.roles().containsAll(strings(context.get("_roles"))) ||
        !currentLabs.containsAll(strings(context.get("_labIds"))) ||
        !evidenceAccessible(u, str(m, "runId"))
      ) {
        m.put(
          "content",
          "账户权限范围已变更，这条历史结果已隐藏。业务记录请按当前权限重新查询。"
        );
        m.put("cards", List.of());
        m.put("citations", List.of());
      } else if (m.get("citations") instanceof List<?> citations) {
        List<Object> allowed = new ArrayList<>();
        for (Object item : citations) {
          var cite = db.object(db.encode(item));
          try {
            knowledge().historicalCitation(u, str(cite, "chunkId"));
            allowed.add(cite);
          } catch (ApiError ignored) {}
        }
        m.put("citations", allowed);
      }
      m.remove("contextJson");
    }
    c.put("messages", messages);
    c.put(
      "runs",
      db.rows(
        "SELECT id,status,mode,error,started_at,finished_at FROM ai_run WHERE conversation_id=? ORDER BY started_at",
        id
      )
    );
    return c;
  }

  @Transactional
  public void deleteConversation(User u, String id) {
    db.one("SELECT id FROM conversation WHERE id=? AND user_id=?", id, u.id());
    require(
      db.count(
        "SELECT COUNT(*) FROM ai_run WHERE conversation_id=? AND status IN ('QUEUED','RUNNING')",
        id
      ) == 0,
      "执行中不能删除对话"
    );
    db.update(
      "DELETE a FROM ai_access a JOIN ai_run r ON r.id=a.run_id WHERE r.conversation_id=?",
      id
    );
    db.update(
      "DELETE e FROM ai_tool_event e JOIN ai_run r ON r.id=e.run_id WHERE r.conversation_id=?",
      id
    );
    db.update("DELETE FROM chat_message WHERE conversation_id=?", id);
    db.update("DELETE FROM ai_run WHERE conversation_id=?", id);
    db.update("DELETE FROM conversation WHERE id=?", id);
  }

  @Transactional
  public Map<String, Object> send(
    User user,
    Map<String, Object> body,
    String key
  ) {
    db.one("SELECT id FROM sys_user WHERE id=? FOR UPDATE", user.id());
    var previous = business.previous(user, "chat", key, body);
    if (previous != null) return previous;
    var result = send(user, body);
    business.remember(user, "chat", key, body, result);
    return result;
  }

  @Transactional
  public Map<String, Object> send(User u, Map<String, Object> b) {
    String text = str(b, "text").trim();
    var photos = strings(b.get("photoIds"));
    require(text.length() <= 10000, "消息过长");
    require(!text.isEmpty() || !photos.isEmpty(), "请输入消息或上传图片");
    require(photos.size() <= 6, "一次最多 6 张图片");
    for (var p : photos) files.requireOwnedImage(u, p);
    String cid = str(b, "conversationId");
    String editId = str(b, "editMessageId");
    if (!editId.isEmpty()) {
      db.one(
        "SELECT id FROM conversation WHERE id=? AND user_id=? FOR UPDATE",
        cid,
        u.id()
      );
      require(
        db.count(
          "SELECT COUNT(*) FROM ai_run WHERE conversation_id=? AND status IN ('QUEUED','RUNNING')",
          cid
        ) == 0,
        "当前对话正在处理，请等待完成"
      );
      var source = db.rows(
        "SELECT * FROM chat_message WHERE conversation_id=? ORDER BY created_at,id",
        cid
      );
      var target = source
        .stream()
        .filter(
          m -> str(m, "id").equals(editId) && str(m, "role").equals("user")
        )
        .findFirst()
        .orElseThrow(() -> new ApiError(404, "NOT_FOUND", "消息不存在"));
      for (String photo : strings(target.get("attachments")))
        require(photos.contains(photo), "编辑时请保留原消息图片");
      String branch = id();
      db.update(
        "INSERT INTO conversation(id,user_id,title,created_at,updated_at) VALUES(?,?,?,?,?)",
        branch,
        u.id(),
        text.isEmpty()
          ? "设备图片与归还"
          : text.substring(0, Math.min(28, text.length())),
        now(),
        now()
      );
      for (var message : source) {
        if (str(message, "id").equals(editId)) break;
        db.update(
          "INSERT INTO chat_message(id,conversation_id,role,content,attachments,cards,citations,run_id,created_at) SELECT ?,?,role,content,attachments,cards,citations,run_id,created_at FROM chat_message WHERE id=?",
          id(),
          branch,
          message.get("id")
        );
      }
      cid = branch;
    }
    if (cid.isEmpty()) {
      cid = id();
      db.update(
        "INSERT INTO conversation(id,user_id,title,created_at,updated_at) VALUES(?,?,?,?,?)",
        cid,
        u.id(),
        text.isEmpty()
          ? "设备图片与归还"
          : text.substring(0, Math.min(28, text.length())),
        now(),
        now()
      );
    } else db.one(
      "SELECT id FROM conversation WHERE id=? AND user_id=? FOR UPDATE",
      cid,
      u.id()
    );
    require(
      db.count(
        "SELECT COUNT(*) FROM ai_run WHERE conversation_id=? AND status IN ('QUEUED','RUNNING')",
        cid
      ) == 0,
      "当前对话正在处理，请等待完成"
    );
    require(
      db.count(
        "SELECT COUNT(*) FROM ai_run WHERE user_id=? AND status IN ('QUEUED','RUNNING')",
        u.id()
      ) < 2,
      "同时最多执行两个请求"
    );
    Map<String, Object> context =
      b.get("context") instanceof Map<?, ?> m
        ? db.object(db.encode(m))
        : new LinkedHashMap<>();
    if (context.containsKey("ticketId")) q.ticket(u, str(context, "ticketId"));
    if (context.containsKey("returnLoanId")) {
      var l = q.loan(u, str(context, "returnLoanId"));
      require(str(l, "userId").equals(u.id()), "只能归还本人设备");
    }
    context.put("_roles", u.roles());
    context.put("_labIds", auth.profile(u).get("labIds"));
    if (context.containsKey("evaluationMode")) {
      auth.admin(u);
      require(
        Set.of("DIRECT", "AGENT_RAG").contains(str(context, "evaluationMode")),
        "评测模式无效"
      );
    }
    String rid = id(),
      grant = UUID.randomUUID() + "-" + UUID.randomUUID();
    String mode = "real";
    db.update(
      "INSERT INTO ai_run(id,conversation_id,user_id,status,mode,query,photo_ids,context_json,grant_hash,started_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
      rid,
      cid,
      u.id(),
      "QUEUED",
      mode,
      text,
      db.encode(photos),
      db.encode(context),
      hash(grant),
      now()
    );
    db.update(
      "INSERT INTO chat_message(id,conversation_id,role,content,attachments,run_id,created_at) VALUES(?,?,?,?,?,?,?)",
      id(),
      cid,
      "user",
      text,
      db.encode(photos),
      rid,
      now()
    );
    db.update("UPDATE conversation SET updated_at=? WHERE id=?", now(), cid);
    TransactionSynchronizationManager.registerSynchronization(
      new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          try {
            executor.submit(() -> execute(rid, grant));
          } catch (RejectedExecutionException e) {
            db.update(
              "UPDATE ai_run SET status='FAILED',error='智能服务繁忙，请稍后重试',finished_at=?,grant_hash=NULL WHERE id=?",
              now(),
              rid
            );
          }
        }
      }
    );
    return Map.of("runId", rid, "conversationId", cid, "status", "QUEUED");
  }

  @Transactional
  public Map<String, Object> retry(User u, String rid) {
    db.one("SELECT id FROM sys_user WHERE id=? FOR UPDATE", u.id());
    var r = db.one(
      "SELECT * FROM ai_run WHERE id=? AND user_id=? FOR UPDATE",
      rid,
      u.id()
    );
    require(
      Set.of("FAILED", "INTERRUPTED").contains(str(r, "status")),
      "当前请求不能重试"
    );
    String cid = str(r, "conversationId");
    var latest = db.rows(
      "SELECT id FROM ai_run WHERE conversation_id=? ORDER BY started_at DESC LIMIT 1",
      cid
    );
    require(
      !latest.isEmpty() && str(latest.getFirst(), "id").equals(rid),
      "请重试最新一条请求"
    );
    require(
      db.count(
        "SELECT COUNT(*) FROM ai_run WHERE user_id=? AND status IN ('QUEUED','RUNNING')",
        u.id()
      ) < 2,
      "同时最多执行两个请求"
    );
    for (String photo : strings(r.get("photoIds")))
      files.requireOwnedImage(u, photo);
    String grant = UUID.randomUUID() + "-" + UUID.randomUUID();
    db.update(
      "UPDATE ai_run SET status='QUEUED',error=NULL,finished_at=NULL,grant_hash=? WHERE id=?",
      hash(grant),
      rid
    );
    TransactionSynchronizationManager.registerSynchronization(
      new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          try {
            executor.submit(() -> execute(rid, grant));
          } catch (RejectedExecutionException e) {
            db.update(
              "UPDATE ai_run SET status='FAILED',error='智能服务繁忙，请稍后重试',finished_at=?,grant_hash=NULL WHERE id=?",
              now(),
              rid
            );
          }
        }
      }
    );
    return Map.of("runId", rid, "conversationId", cid, "status", "QUEUED");
  }

  void execute(String rid, String grant) {
    try {
      db.update(
        "UPDATE ai_run SET status='RUNNING',started_at=UTC_TIMESTAMP(3) WHERE id=? AND status='QUEUED'",
        rid
      );
      var r = db.one("SELECT * FROM ai_run WHERE id=?", rid);
      User user = auth.user(str(r, "userId"));
      List<Map<String, Object>> images = new ArrayList<>();
      for (String pid : strings(r.get("photoIds"))) {
        var f = files.owned(user, pid);
        images.add(
          Map.of(
            "id",
            pid,
            "name",
            f.get("name"),
            "mimeType",
            f.get("mimeType"),
            "base64",
            Base64.getEncoder().encodeToString(files.read(pid))
          )
        );
      }
      var visible = (List<?>) conversation(user, str(r, "conversationId")).get(
        "messages"
      );
      var messages = visible
        .stream()
        .skip(Math.max(0, visible.size() - 12))
        .map(item -> {
          var m = db.object(db.encode(item));
          return Map.of("role", m.get("role"), "content", m.get("content"));
        })
        .toList();
      var result = request(
        "/internal/agent",
        Map.ofEntries(
          Map.entry("runId", rid),
          Map.entry("grant", grant),
          Map.entry("query", r.get("query")),
          Map.entry("messages", messages),
          Map.entry("images", images),
          Map.entry("context", r.get("contextJson")),
          Map.entry("user", Map.of("name", user.name(), "roles", user.roles())),
          Map.entry("config", settings(true))
        )
      );
      List<Object> citations = new ArrayList<>();
      if (
        result.get("citations") instanceof List<?> list
      ) for (Object item : list) {
        if (item instanceof Map<?, ?> m) {
          String chunk = String.valueOf(m.get("chunkId"));
          try {
            citations.add(knowledge().citation(user, chunk));
          } catch (ApiError ignored) {}
        }
      }
      user = auth.user(user.id());
      require(
        evidenceAccessible(user, rid),
        "本次执行的数据权限已变更，请按当前权限重新查询"
      );
      String content = str(result, "text");
      require(!content.isBlank(), "模型没有返回有效回复");
      var cards = result.getOrDefault("cards", List.of());
      completeRun(r, content, cards, citations, result);
    } catch (Exception e) {
      String message =
        e instanceof ApiError
          ? e.getMessage()
          : "智能请求失败，请检查模型设置或稍后重试";
      try {
        if (completeBusinessReceipt(rid, message)) return;
      } catch (Exception ignored) {}
      db.update(
        "UPDATE ai_run SET status='FAILED',error=?,finished_at=?,grant_hash=NULL WHERE id=?",
        message,
        now(),
        rid
      );
    }
  }

  // Lazy lookup avoids a lifecycle cycle between knowledge indexing and the gateway.
  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.context.ApplicationContext application;

  Knowledge knowledge() {
    return application.getBean(Knowledge.class);
  }

  User runUser(String rid, String grant) {
    var r = db.one("SELECT * FROM ai_run WHERE id=?", rid);
    if (
      !str(r, "status").equals("RUNNING") ||
      grant == null ||
      !MessageDigest.isEqual(
        hash(grant).getBytes(StandardCharsets.UTF_8),
        str(r, "grantHash").getBytes(StandardCharsets.UTF_8)
      ) ||
      Instant.parse(str(r, "startedAt")).isBefore(
        Instant.now().minusSeconds(180)
      )
    ) throw new ApiError(403, "RUN_EXPIRED", "本次执行授权已失效");
    return auth.user(str(r, "userId"));
  }

  Map<String, Object> run(User u, String id) {
    var r = db.one(
      "SELECT id,conversation_id,user_id,status,mode,error,started_at,finished_at,result_json FROM ai_run WHERE id=? AND user_id=?",
      id,
      u.id()
    );
    Object result = r.remove("resultJson");
    r.put("usage", result instanceof Map<?, ?> m ? m.get("usage") : null);
    r.put(
      "events",
      db.rows(
        "SELECT tool_name,summary,duration_ms,created_at FROM ai_tool_event WHERE run_id=? ORDER BY created_at",
        id
      )
    );
    return r;
  }

  Object tool(String rid, String grant, String tool, Map<String, Object> args) {
    User u = runUser(rid, grant);
    require(
      db.update(
        "UPDATE ai_run SET tool_calls=tool_calls+1 WHERE id=? AND status='RUNNING' AND tool_calls<6",
        rid
      ) == 1,
      "本轮工具调用已达上限"
    );
    long started = System.nanoTime();
    Object out;
    var runContext = db
      .one("SELECT context_json FROM ai_run WHERE id=?", rid)
      .get("contextJson");
    String selectedLab = str(db.object(db.encode(runContext)), "labId");
    if (
      !selectedLab.isBlank() &&
      Set.of(
        "search_equipment",
        "query_loans",
        "query_tickets",
        "get_statistics"
      ).contains(tool)
    ) {
      args = new LinkedHashMap<>(args);
      args.put("labId", selectedLab);
    }
    try {
      switch (tool) {
        case "search_equipment" -> {
          var rows = q.equipment(
            u,
            str(args, "search"),
            str(args, "labId"),
            "",
            bool(args.get("availableOnly"))
          );
          out = Map.of(
            "items",
            rows.stream().limit(100).toList(),
            "total",
            rows.size(),
            "asOf",
            Instant.now().toString()
          );
        }
        case "query_loans" -> {
          var rows = q.loans(u, bool(args.get("activeOnly")));
          String lab = str(args, "labId");
          if (!lab.isEmpty()) rows = rows
            .stream()
            .filter(l -> str(l, "labId").equals(lab))
            .toList();
          if (bool(args.get("onlyMine"))) rows = rows
            .stream()
            .filter(l -> str(l, "userId").equals(u.id()))
            .toList();
          out = Map.of(
            "items",
            rows.stream().limit(100).toList(),
            "total",
            rows.size(),
            "currentBorrowed",
            rows
              .stream()
              .filter(l -> l.get("returnedAt") == null)
              .count(),
            "returned",
            rows
              .stream()
              .filter(l -> l.get("returnedAt") != null)
              .count(),
            "overdue",
            rows
              .stream()
              .filter(l -> bool(l.get("overdue")))
              .count(),
            "asOf",
            Instant.now().toString()
          );
        }
        case "query_tickets" -> {
          var rows = q.tickets(u);
          String state = str(args, "status"),
            lab = str(args, "labId"),
            search = str(args, "search");
          var filtered = rows
            .stream()
            .filter(t -> state.isEmpty() || str(t, "status").equals(state))
            .filter(t -> lab.isEmpty() || str(t, "labId").equals(lab))
            .filter(t -> search.isEmpty() || db.encode(t).contains(search))
            .toList();
          out = Map.of(
            "items",
            filtered.stream().limit(100).toList(),
            "total",
            filtered.size(),
            "openCount",
            filtered
              .stream()
              .filter(
                t ->
                  !Set.of("CLOSED", "REJECTED", "CANCELLED").contains(
                    str(t, "status")
                  )
              )
              .count(),
            "asOf",
            Instant.now().toString()
          );
        }
        case "get_statistics" -> out = q.statistics(
          u,
          ((Number) args.getOrDefault("days", 30)).intValue(),
          str(args, "labId")
        );
        case "get_ticket_context" -> out = q.ticket(
          u,
          required(args, "ticketId", 36)
        );
        case "list_laboratories" -> out = Map.of("items", q.labs());
        case "search_knowledge" -> out = Map.of(
          "items",
          knowledge().search(
            u,
            required(args, "query", 1000),
            str(args, "model"),
            str(args, "category")
          )
        );
        case "create_reservation" -> {
          var r = db.one("SELECT query FROM ai_run WHERE id=?", rid);
          writeIntent(str(r, "query"));
          require(
            str(r, "query").matches(
              "(?s).*(帮我预约|替我预约|给我预约|我要预约|我想预约|帮我借|替我借|给我借|我要借|我想借|申请借用|提交预约).*"
            ),
            "请明确提出预约请求，或点击设备卡片申请借用"
          );
          out = business.reserve(
            u,
            args,
            rid + ":reserve:" + str(args, "equipmentId")
          );
        }
        case "return_loan" -> {
          var r = db.one("SELECT * FROM ai_run WHERE id=?", rid);
          writeIntent(str(r, "query"));
          var photos = strings(r.get("photoIds"));
          require(!photos.isEmpty(), "本轮需要上传归还图片");
          String lid = required(args, "loanId", 36);
          var loan = q.loan(u, lid);
          require(str(loan, "userId").equals(u.id()), "只能归还本人设备");
          var active = q
            .loans(u, true)
            .stream()
            .filter(l -> str(l, "userId").equals(u.id()))
            .toList();
          Map<String, Object> context = db.object(
            db.encode(r.get("contextJson"))
          );
          boolean exact =
            active.size() == 1 ||
            (str(r, "query").contains(str(loan, "equipmentName")) &&
              active
                .stream()
                .filter(l -> str(r, "query").contains(str(l, "equipmentName")))
                .count() == 1) ||
            str(context, "returnLoanId").equals(lid) ||
            str(r, "query").contains(str(loan, "assetNo")) ||
            str(args, "identifiedAssetNo").equals(str(loan, "assetNo"));
          require(exact, "有多台未归还设备，请选择对应设备或说明资产编号");
          require(
            str(r, "query").matches(
              "(?s).*(归还|还设备|还了|还一下|还回|return).*"
            ) || bool(args.get("imageShowsReturn")),
            "没有确定归还意图，请点击归还卡片"
          );
          Map<String, Object> body = Map.of(
            "photoIds",
            photos,
            "note",
            "通过 AI 对话归还：" + str(r, "query")
          );
          out = business.returnLoan(u, lid, body, rid + ":return:" + lid);
        }
        case "report_fault" -> {
          var r = db.one("SELECT query,photo_ids FROM ai_run WHERE id=?", rid);
          writeIntent(str(r, "query"));
          require(
            str(r, "query").matches(
              "(?s).*(帮我报修|我要报修|提交报修|报修设备|提交故障|报告故障|创建工单).*"
            ),
            "请明确提出报修请求"
          );
          args = new LinkedHashMap<>(args);
          args.put("photoIds", strings(r.get("photoIds")));
          out = business.createTicket(u, args, rid + ":fault");
        }
        default -> throw new ApiError(
          403,
          "TOOL_FORBIDDEN",
          "不允许调用该工具"
        );
      }
    } catch (ApiError error) {
      db.update(
        "INSERT INTO ai_tool_event VALUES(?,?,?,?,?,?)",
        id(),
        rid,
        tool,
        "未完成：" + error.getMessage(),
        (System.nanoTime() - started) / 1_000_000,
        now()
      );
      throw error;
    }
    if (out instanceof Map<?, ?> map) {
      Object items = map.get("items");
      if (items instanceof List<?> rows) for (Object item : rows) {
        var row = db.object(db.encode(item));
        if (tool.equals("query_tickets")) access(rid, "TICKET", str(row, "id"));
        if (tool.equals("query_loans")) access(rid, "LOAN", str(row, "id"));
        if (tool.equals("search_knowledge")) access(
          rid,
          "CHUNK",
          str(row, "chunkId")
        );
      }
      if (
        tool.equals("get_ticket_context") || tool.equals("report_fault")
      ) access(rid, "TICKET", str(db.object(db.encode(out)), "id"));
      if (tool.equals("return_loan")) access(
        rid,
        "LOAN",
        str(db.object(db.encode(out)), "id")
      );
    }
    db.update(
      "INSERT INTO ai_tool_event VALUES(?,?,?,?,?,?)",
      id(),
      rid,
      tool,
      "已按当前账户权限执行",
      (System.nanoTime() - started) / 1_000_000,
      now()
    );
    return out;
  }
}
