/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  images: {
    remotePatterns: [
      { protocol: 'https', hostname: '**' },
    ],
  },
  experimental: {
    // 预留：需要时打开 partial prerendering
    // ppr: true,
  },
};

export default nextConfig;
