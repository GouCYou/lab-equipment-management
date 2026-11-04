import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";
export default defineConfig({
  plugins: [vue()],
  server: {
    host: "0.0.0.0",
    port: 5188,
    strictPort: true,
    proxy: {
      "/api": {
        target: process.env.VITE_PROXY_TARGET || "http://127.0.0.1:18180",
        changeOrigin: true,
      },
    },
  },
  build: { chunkSizeWarningLimit: 1600 },
  test: { environment: "jsdom", restoreMocks: true },
});
