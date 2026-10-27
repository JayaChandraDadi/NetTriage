import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In development the service's REST API is proxied, so the app calls /api/*.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5175,
    proxy: {
      "/api": process.env.NETTRIAGE_API ?? "http://localhost:8095",
    },
  },
});
