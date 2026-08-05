import { Sidebar } from '@/components/home/Sidebar';
import { PlaceholderPage } from '@/components/common/PlaceholderPage';

export default function Page() {
  return (
    <div className="min-h-screen pl-[72px]">
      <Sidebar />
      <main className="mx-auto w-full max-w-[1200px] px-4 py-6 sm:px-6">
        <PlaceholderPage title="资产" desc="素材库 · 历史生成结果 · 文件管理" emoji="📁" />
      </main>
    </div>
  );
}
