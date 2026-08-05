import type { Config } from 'tailwindcss';

const config: Config = {
  content: ['./src/**/*.{ts,tsx,mdx}'],
  darkMode: 'class',
  theme: {
    container: {
      center: true,
      padding: '1rem',
      screens: { '2xl': '1400px' },
    },
    extend: {
      colors: {
        bg: {
          DEFAULT: '#ffffff',
          soft: '#f7f8fb',
          card: '#ffffff',
          line: '#e7e9ef',
        },
        fg: {
          DEFAULT: '#0f172a',
          muted: '#475569',
          subtle: '#94a3b8',
        },
        // 单色品牌：纯蓝紫渐变，参考聚融风格
        brand: {
          DEFAULT: '#3b6bff',       // 主蓝
          glow: '#7c8cff',          // 浅蓝紫
          dark: '#2a4ed8',          // 深蓝
          50: '#eef2ff',
          100: '#e0e7ff',
          500: '#3b6bff',
          600: '#2a4ed8',
          700: '#1e3bb0',
        },
        accent: {
          // 保持一个紫色作为辅助点缀（轻量使用）
          purple: '#8b8cff',
        },
      },
      fontFamily: {
        sans: ['ui-sans-serif', 'system-ui', '-apple-system', 'Segoe UI', 'Roboto', 'sans-serif'],
        mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
      },
      borderRadius: {
        xl: '14px',
        '2xl': '20px',
      },
      boxShadow: {
        soft: '0 1px 2px rgba(15,23,42,0.04), 0 8px 24px -8px rgba(15,23,42,0.08)',
        glow: '0 0 40px -10px rgba(59,107,255,0.40)',
      },
      keyframes: {
        'pulse-glow': {
          '0%,100%': { boxShadow: '0 0 0 0 rgba(59,107,255,0.40)' },
          '50%': { boxShadow: '0 0 0 12px rgba(59,107,255,0)' },
        },
        shimmer: {
          '0%': { backgroundPosition: '-200% 0' },
          '100%': { backgroundPosition: '200% 0' },
        },
      },
      animation: {
        'pulse-glow': 'pulse-glow 1.6s ease-out infinite',
        shimmer: 'shimmer 1.6s linear infinite',
      },
    },
  },
  plugins: [],
};

export default config;
