import type { Metadata } from 'next';
import './globals.css';
import { MaterialsProvider } from '@/contexts/MaterialsContext';
import { MediaPickerProvider } from '@/contexts/MediaPickerContext';
import { AuthStateListener } from '@/components/common/AuthStateListener';
import { LoginGate } from '@/components/common/LoginGate';

export const metadata: Metadata = {
  title: 'JRai · AIGC 平台',
  description: '为电商而生的 AIGC 平台',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN">
      <body className="min-h-screen">
        <AuthStateListener />
        <MaterialsProvider>
          <MediaPickerProvider>
            {children}
            <LoginGate />
          </MediaPickerProvider>
        </MaterialsProvider>
      </body>
    </html>
  );
}
