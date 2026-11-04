package com.lab.equipment;

import static com.lab.equipment.ApiError.require;
import static com.lab.equipment.Db.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

@Service
public class Knowledge {

  final Db db;
  final Auth auth;
  final Files files;
  final AiGateway ai;

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.transaction.PlatformTransactionManager transactions;

  Knowledge(Db db, Auth auth, Files files, AiGateway ai) {
    this.db = db;
    this.auth = auth;
    this.files = files;
    this.ai = ai;
  }

  static boolean visible(Db db, User u, Map<String, Object> d) {
    if (u.admin()) return true;
    if (str(d, "visibility").equals("PUBLIC")) return true;
    String lab = str(d, "labId");
    if (
      u.is("LAB_STAFF") &&
      db.count(
        "SELECT COUNT(*) FROM lab_staff_scope WHERE user_id=? AND lab_id=?",
        u.id(),
        lab
      ) > 0
    ) return true;
    return (
      u.tech() &&
      str(d, "visibility").equals("MAINTENANCE") &&
      db.count(
        "SELECT COUNT(*) FROM fault_ticket WHERE assignee_id=? AND lab_id=? AND status NOT IN ('CLOSED','REJECTED','CANCELLED')",
        u.id(),
        lab
      ) > 0
    );
  }

  boolean manage(User u, Map<String, Object> d) {
    if (u.admin()) return true;
    if (u.is("LAB_STAFF") && !str(d, "labId").isEmpty()) {
      auth.lab(u, str(d, "labId"));
      return true;
    }
    return false;
  }

  boolean canManage(User u, Map<String, Object> d) {
    try {
      return manage(u, d);
    } catch (ApiError e) {
      return false;
    }
  }

  List<Map<String, Object>> list(User u) {
    var rows = db.rows(
      "SELECT d.*,u.name AS creator_name,v.status AS index_status,v.chunk_count FROM knowledge_document d LEFT JOIN knowledge_version v ON v.id=(SELECT x.id FROM knowledge_version x WHERE x.document_id=d.id ORDER BY x.version_no DESC LIMIT 1) LEFT JOIN sys_user u ON u.id=d.created_by ORDER BY d.created_at DESC"
    );
    return rows
      .stream()
      .filter(d -> visible(db, u, d))
      .filter(
        d ->
          canManage(u, d) ||
          (bool(d.get("enabled")) && d.get("publishedVersionId") != null)
      )
      .toList();
  }

  Map<String, Object> detail(User u, String id) {
    var d = db.one("SELECT * FROM knowledge_document WHERE id=?", id);
    if (
      !visible(db, u, d) ||
      (!canManage(u, d) &&
        (!bool(d.get("enabled")) || d.get("publishedVersionId") == null))
    ) throw new ApiError(404, "NOT_FOUND", "资料不存在或不可访问");
    var versions = db.rows(
      "SELECT * FROM knowledge_version WHERE document_id=? ORDER BY version_no DESC",
      id
    );
    if (!canManage(u, d)) versions = versions
      .stream()
      .filter(v -> str(v, "id").equals(str(d, "publishedVersionId")))
      .toList();
    d.put("versions", versions);
    return d;
  }

  @Transactional
  public Map<String, Object> save(User u, String id, Map<String, Object> b) {
    if (!u.staff()) throw new ApiError(
      403,
      "FORBIDDEN",
      "只有实验员或管理员可以管理知识"
    );
    String lab = str(b, "labId");
    if (!u.admin()) {
      require(!lab.isBlank(), "请选择负责的实验室");
      auth.lab(u, lab);
    }
    String visibility = str(b, "visibility");
    require(
      Set.of("PUBLIC", "LAB", "MAINTENANCE").contains(visibility),
      "资料范围无效"
    );
    require(
      visibility.equals("PUBLIC") || !lab.isBlank(),
      "非公开资料需要指定实验室"
    );
    if (id == null) {
      id = id();
      db.update(
        "INSERT INTO knowledge_document(id,title,category,model,lab_id,visibility,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)",
        id,
        required(b, "title", 160),
        str(b, "category"),
        str(b, "model"),
        lab.isBlank() ? null : lab,
        visibility,
        u.id(),
        now()
      );
    } else {
      var d = db.one(
        "SELECT * FROM knowledge_document WHERE id=? FOR UPDATE",
        id
      );
      require(manage(u, d), "没有资料管理权限");
    }
    String text = str(b, "text"),
      fid = str(b, "fileId");
    require(!text.isBlank() || !fid.isBlank(), "请输入资料正文或上传文件");
    require(text.length() <= 500000, "资料文字过长");
    if (!fid.isBlank()) files.owned(u, fid);
    String vid = Db.id();
    int version = (int) db.count(
      "SELECT COALESCE(MAX(version_no),0)+1 FROM knowledge_version WHERE document_id=?",
      id
    );
    db.update(
      "INSERT INTO knowledge_version(id,document_id,version_no,file_id,text_content,status,created_at,title,model,category,visibility,lab_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
      vid,
      id,
      version,
      fid.isBlank() ? null : fid,
      text,
      "UNINDEXED",
      now(),
      required(b, "title", 160),
      str(b, "model"),
      str(b, "category"),
      visibility,
      lab.isBlank() ? null : lab
    );
    db.audit(u.id(), "SAVE_KNOWLEDGE", id, "创建资料版本 " + version);
    return Map.of("id", id, "versionId", vid);
  }

  @Transactional
  public Map<String, Object> action(
    User u,
    String did,
    String action,
    Map<String, Object> b
  ) {
    var d = db.one(
      "SELECT * FROM knowledge_document WHERE id=? FOR UPDATE",
      did
    );
    require(manage(u, d), "没有资料管理权限");
    if (action.equals("toggle")) {
      db.update(
        "UPDATE knowledge_document SET enabled=? WHERE id=?",
        bool(b.get("enabled")),
        did
      );
    } else {
      String vid = required(b, "versionId", 36);
      var v = db.one(
        "SELECT * FROM knowledge_version WHERE id=? AND document_id=?",
        vid,
        did
      );
      if (action.equals("publish")) {
        require(str(v, "status").equals("READY"), "索引成功后才能发布");
        db.update(
          "UPDATE knowledge_document SET published_version_id=?,enabled=TRUE,title=?,model=?,category=?,visibility=?,lab_id=? WHERE id=?",
          vid,
          v.get("title"),
          v.get("model"),
          v.get("category"),
          v.get("visibility"),
          v.get("labId"),
          did
        );
        db.update(
          "UPDATE knowledge_version SET published=(id=?),published_at=CASE WHEN id=? THEN COALESCE(published_at,UTC_TIMESTAMP(3)) ELSE published_at END WHERE document_id=?",
          vid,
          vid,
          did
        );
      } else if (action.equals("index")) {
        require(!str(v, "status").equals("INDEXING"), "资料正在索引");
        db.update(
          "UPDATE knowledge_version SET status='INDEXING',error=NULL WHERE id=?",
          vid
        );
        String jid = Db.id();
        db.update(
          "INSERT INTO job_task VALUES(?,?,?,?,?,?,?)",
          jid,
          "INDEX",
          vid,
          "QUEUED",
          null,
          now(),
          now()
        );
        TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              try {
                ai.enqueue(() -> index(vid, jid));
              } catch (Exception e) {
                db.update(
                  "UPDATE knowledge_version SET status='FAILED',error='队列繁忙，请重试' WHERE id=?",
                  vid
                );
                db.update(
                  "UPDATE job_task SET status='FAILED',error='队列繁忙，请重试',updated_at=? WHERE id=?",
                  now(),
                  jid
                );
              }
            }
          }
        );
      } else throw new ApiError(404, "NOT_FOUND", "不支持的资料操作");
    }
    db.audit(u.id(), "KNOWLEDGE_" + action, did, "资料操作");
    return detail(u, did);
  }

  void index(String vid, String jid) {
    try {
      db.update(
        "UPDATE job_task SET status='RUNNING',updated_at=? WHERE id=?",
        now(),
        jid
      );
      var v = db.one("SELECT v.* FROM knowledge_version v WHERE v.id=?", vid);
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("versionId", vid);
      payload.put("documentId", v.get("documentId"));
      payload.put("text", v.get("textContent"));
      payload.put("config", ai.settings(true));
      String fid = str(v, "fileId");
      if (!fid.isBlank()) {
        var f = db.one("SELECT * FROM file_object WHERE id=?", fid);
        payload.put(
          "file",
          Map.of(
            "name",
            f.get("name"),
            "base64",
            Base64.getEncoder().encodeToString(files.read(fid))
          )
        );
      }
      var result = ai.request("/internal/knowledge/index", payload);
      Object chunks = result.get("chunks");
      require(chunks instanceof List<?>, "索引返回无效结果");
      new org.springframework.transaction.support.TransactionTemplate(
        transactions
      ).executeWithoutResult(tx -> {
        db.update("DELETE FROM knowledge_chunk WHERE version_id=?", vid);
        for (Object item : (List<?>) chunks) {
          var c = db.object(db.encode(item));
          db.update(
            "INSERT INTO knowledge_chunk VALUES(?,?,?,?,?)",
            c.get("id"),
            vid,
            c.get("index"),
            c.get("text"),
            c.get("locator")
          );
        }
        db.update(
          "UPDATE knowledge_version SET status='READY',text_content=?,chunk_count=? WHERE id=?",
          str(result, "text"),
          ((List<?>) chunks).size(),
          vid
        );
        db.update(
          "UPDATE job_task SET status='SUCCEEDED',updated_at=? WHERE id=?",
          now(),
          jid
        );
      });
    } catch (Exception e) {
      String msg =
        e instanceof ApiError ? e.getMessage() : "资料解析或向量索引失败";
      db.update(
        "UPDATE knowledge_version SET status='FAILED',error=? WHERE id=?",
        msg,
        vid
      );
      db.update(
        "UPDATE job_task SET status='FAILED',error=?,updated_at=? WHERE id=?",
        msg,
        now(),
        jid
      );
    }
  }

  List<Map<String, Object>> search(
    User u,
    String query,
    String model,
    String category
  ) {
    var docs = list(u)
      .stream()
      .filter(
        d -> bool(d.get("enabled")) && d.get("publishedVersionId") != null
      )
      .filter(
        d ->
          model.isBlank() ||
          str(d, "model").isBlank() ||
          str(d, "model").equalsIgnoreCase(model)
      )
      .filter(
        d ->
          category.isBlank() ||
          str(d, "category").isBlank() ||
          str(d, "category").equals(category)
      )
      .toList();
    if (docs.isEmpty()) return List.of();
    var ids = docs
      .stream()
      .map(d -> str(d, "publishedVersionId"))
      .toList();
    String normalized = query.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
    Set<String> terms = new HashSet<>();
    for (int i=0; i<normalized.length()-1; i++) terms.add(normalized.substring(i,i+2));
    if (normalized.length()==1) terms.add(normalized);
    List<Map<String,Object>> matches = new ArrayList<>();
    for (String version : ids) for (var chunk : db.rows("SELECT id,text_content FROM knowledge_chunk WHERE version_id=?",version)) {
      var c = citation(u,str(chunk,"id"));
      String text = (str(c,"title")+str(c,"excerpt")).toLowerCase(java.util.Locale.ROOT);
      long score = terms.stream().filter(text::contains).count();
      if(score>0) { c.put("score",score); matches.add(c); }
    }
    matches.sort((a,b)->Long.compare(((Number)b.get("score")).longValue(),((Number)a.get("score")).longValue()));
    return matches.stream().limit(6).toList();
  }

  Map<String, Object> citation(User u, String chunk) {
    return citation(u, chunk, false);
  }

  Map<String, Object> historicalCitation(User u, String chunk) {
    return citation(u, chunk, true);
  }

  Map<String, Object> citation(User u, String chunk, boolean historical) {
    var c = db.one(
      "SELECT c.id AS chunk_id,c.text_content AS excerpt,c.locator,v.version_no,v.id AS version_id,v.published_at,d.id AS document_id,COALESCE(v.title,d.title) AS title,COALESCE(v.model,d.model) AS model,COALESCE(v.category,d.category) AS category,d.lab_id,d.visibility,d.enabled,d.published_version_id FROM knowledge_chunk c JOIN knowledge_version v ON v.id=c.version_id JOIN knowledge_document d ON d.id=v.document_id WHERE c.id=?",
      chunk
    );
    require(
      visible(db, u, c) &&
        (historical
          ? c.get("publishedAt") != null
          : bool(c.get("enabled")) &&
            str(c, "versionId").equals(str(c, "publishedVersionId"))),
      "引用资料已停用或无权访问"
    );
    c.remove("publishedVersionId");
    return c;
  }
}
