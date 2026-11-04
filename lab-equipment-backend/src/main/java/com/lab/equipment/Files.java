package com.lab.equipment;

import static com.lab.equipment.ApiError.require;
import static com.lab.equipment.Db.*;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class Files {

  final Db db;
  final Queries q;
  final Path root;

  Files(Db db, Queries q, Environment env) {
    this.db = db;
    this.q = q;
    this.root = Path.of(
      env.getProperty("KNOWLEDGE_DIR", ".runtime/local/knowledge")
    )
      .toAbsolutePath()
      .normalize()
      .resolve("uploads");
    try {
      java.nio.file.Files.createDirectories(root);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  Map<String, Object> upload(User u, MultipartFile file) {
    try {
      require(
        !file.isEmpty() && file.getSize() <= 20 * 1024 * 1024,
        "文件不能为空且不得超过 20MB"
      );
      String name =
        file.getOriginalFilename() == null
          ? "文件"
          : Path.of(file.getOriginalFilename().replace('\\', '/'))
              .getFileName()
              .toString();
      String ext = name.contains(".")
        ? name.substring(name.lastIndexOf('.') + 1).toLowerCase()
        : "";
      String mime = switch (ext) {
        case "png" -> "image/png";
        case "jpg", "jpeg" -> "image/jpeg";
        case "pdf" -> "application/pdf";
        case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        case "txt", "md" -> "text/plain";
        default -> throw new ApiError(
          422,
          "UNSUPPORTED_FILE",
          "支持 JPG、PNG、PDF、DOCX、TXT 和 Markdown"
        );
      };
      byte[] bytes = file.getBytes();
      if (mime.startsWith("image/")) {
        try (
          var stream = ImageIO.createImageInputStream(
            new ByteArrayInputStream(bytes)
          )
        ) {
          var it = ImageIO.getImageReaders(stream);
          require(it.hasNext(), "图片无法读取，请上传真实 JPG 或 PNG");
          var reader = it.next();
          try {
            reader.setInput(stream);
            require(
              (long) reader.getWidth(0) * reader.getHeight(0) <= 24_000_000,
              "图片分辨率过大，请压缩后上传"
            );
            require(reader.read(0) != null, "图片格式无效");
          } finally {
            reader.dispose();
          }
        }
      }
      String id = id(),
        key = id + "." + ext;
      Path target = root.resolve(key);
      java.nio.file.Files.write(target, bytes);
      String digest = HexFormat.of().formatHex(
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
      );
      db.update(
        "INSERT INTO file_object VALUES(?,?,?,?,?,?,?,?)",
        id,
        u.id(),
        key,
        name,
        mime,
        bytes.length,
        digest,
        now()
      );
      return db.one(
        "SELECT id,name,mime_type,size_bytes FROM file_object WHERE id=?",
        id
      );
    } catch (ApiError e) {
      throw e;
    } catch (Exception e) {
      throw new ApiError(
        422,
        "UPLOAD_FAILED",
        "文件无法保存，请检查格式后重试"
      );
    }
  }

  Map<String, Object> owned(User u, String id) {
    return db.one(
      "SELECT * FROM file_object WHERE id=? AND owner_id=?",
      id,
      u.id()
    );
  }

  void requireOwnedImage(User u, String id) {
    var f = owned(u, id);
    require(
      str(f, "mimeType").startsWith("image/"),
      "归还与报修附件必须是图片"
    );
  }

  byte[] read(String id) {
    var f = db.one("SELECT * FROM file_object WHERE id=?", id);
    Path p = root.resolve(str(f, "storageKey")).normalize();
    require(p.startsWith(root), "文件路径无效");
    try {
      return java.nio.file.Files.readAllBytes(p);
    } catch (IOException e) {
      throw new ApiError(404, "FILE_NOT_FOUND", "文件不存在");
    }
  }

  boolean accessible(User u, String id) {
    var f = db.one("SELECT * FROM file_object WHERE id=?", id);
    if (str(f, "ownerId").equals(u.id())) return true;
    if (
      q
        .loans(u, false)
        .stream()
        .anyMatch(l -> strings(l.get("returnPhotoIds")).contains(id))
    ) return true;
    if (
      q
        .tickets(u)
        .stream()
        .anyMatch(t -> strings(t.get("photoIds")).contains(id))
    ) return true;
    return db
      .rows(
        "SELECT d.* FROM knowledge_document d JOIN knowledge_version v ON v.document_id=d.id WHERE v.file_id=? AND d.enabled=TRUE AND d.published_version_id=v.id",
        id
      )
      .stream()
      .anyMatch(d -> Knowledge.visible(db, u, d));
  }

  ResponseEntity<byte[]> download(User u, String id) {
    if (!accessible(u, id)) throw new ApiError(
      404,
      "NOT_FOUND",
      "文件不存在或不可访问"
    );
    var f = db.one("SELECT * FROM file_object WHERE id=?", id);
    return ResponseEntity.ok()
      .contentType(MediaType.parseMediaType(str(f, "mimeType")))
      .header("X-Content-Type-Options", "nosniff")
      .header(
        "Content-Disposition",
        ContentDisposition.inline()
          .filename(str(f, "name"), java.nio.charset.StandardCharsets.UTF_8)
          .build()
          .toString()
      )
      .cacheControl(CacheControl.noStore())
      .body(read(id));
  }
}
