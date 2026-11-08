import { afterEach, describe, expect, it, vi } from "vitest";
import { requestId } from "./request-id";
afterEach(() => vi.unstubAllGlobals());
describe("局域网 HTTP 请求编号", () => {
  it("randomUUID 不可用时仍生成有效且不同的请求编号", () => {
    const getRandomValues = crypto.getRandomValues.bind(crypto);
    vi.stubGlobal("crypto", { getRandomValues });
    const ids = Array.from({ length: 100 }, requestId);
    expect(new Set(ids).size).toBe(100);
    for (const id of ids)
      expect(id).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
      );
  });
  it("安全上下文继续使用浏览器 UUID", () => {
    vi.stubGlobal("crypto", { randomUUID: () => "native-id" });
    expect(requestId()).toBe("native-id");
  });
});
