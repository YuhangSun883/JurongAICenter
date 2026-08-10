import type { Metadata } from 'next';
import './globals.css';
import { MaterialsProvider } from '@/contexts/MaterialsContext';
import { MediaPickerProvider } from '@/contexts/MediaPickerContext';
import { LoginGate } from '@/components/common/LoginGate';

export const metadata: Metadata = {
  title: 'JRai · AIGC 平台',
  description: '为电商而生的 AIGC 平台',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN">
      <body className="min-h-screen">
        {/*
          LoginGate 必须放在最外层(包住 Providers),原因:
            1. 启动时从 localStorage 同步 token 到内存(否则 token 在 LS 里有但 in-memory 是 null)
            2. 未登录强制跳 /login?from=...
            3. 启动 60s watchdog + 用户活动 refresh
          LoginGate 自己处理 /login 页的特殊情况(只 bootstrap 不跳转),所以不会和登录页冲突
        */}
        <LoginGate>
          <MaterialsProvider>
            <MediaPickerProvider>{children}</MediaPickerProvider>
          </MaterialsProvider>
        </LoginGate>
      </body>
    </html>
  );
}
