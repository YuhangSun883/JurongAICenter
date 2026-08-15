import type { Metadata } from 'next';

export const metadata: Metadata = {
  title: '全域智像后台',
  description: '全域智像后台管理系统',
};

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return children;
}
