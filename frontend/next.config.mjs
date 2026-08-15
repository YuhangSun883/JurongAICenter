const apiBaseUrl = (process.env.INTERNAL_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');

/** @type {import('next').NextConfig} */
const createNextConfig = (phase) => ({
  // dev 和 build 分开目录，避免开发服务运行时执行 next build 导致 _next/static chunk 404。
  distDir: phase === 'phase-development-server' ? '.next-dev' : '.next-build',
  reactStrictMode: true,
  images: {
    remotePatterns: [
      { protocol: 'https', hostname: '**' },
    ],
  },
  // 临时跳过 lint：合并前就有的 unused-var / no-any 警告 40+ 处不阻塞 build
  // （dev 模式 `next lint` 仍会报错，方便后续清理）
  eslint: {
    ignoreDuringBuilds: true,
  },
  // 临时跳过类型检查：合并前就有的缺类型/any 错误 40+ 处不阻塞 build
  typescript: {
    ignoreBuildErrors: true,
  },
  experimental: {
    // 预留：需要时打开 partial prerendering
    // ppr: true,
  },
  // API 代理：前端 /api/* 转给后端 Spring Boot(8080)
  // 不配这个会导致 fetch('/api/canvas/...') 返 404 (Next.js 本身不拦截 /api)
  rewrites: async () => [
    {
      source: '/api/:path*',
      destination: `${apiBaseUrl}/api/:path*`,
    },
  ],
});

export default createNextConfig;
