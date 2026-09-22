import { cors } from "hono/cors";

export const apiCors = cors({
  origin: "*",
  allowMethods: ["GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"],
  allowHeaders: [
    "Content-Type",
    "Authorization",
    "X-Request-ID",
    "X-Firefly-Device-ID",
    "X-Firefly-Crypto-Version",
    "X-Firefly-Challenge",
  ],
  exposeHeaders: ["X-Request-ID", "X-Firefly-Crypto-Version"],
  maxAge: 86400,
});
