import { Suspense } from 'react';
import { Sidebar } from '@/components/home/Sidebar';
import { AssetsView } from './AssetsView';

export default function Page() {
  return (
    <div className="min-h-screen pl-[72px]">
      <Sidebar />
      <main className="min-h-screen bg-[#f7f7f8]">
        {/* V21：useSearchParams 需要 Suspense 边界（Next.js 14 要求） */}
        <Suspense fallback={<div className="p-10 text-center text-[#8a909b]">加载中…</div>}>
          <AssetsView />
        </Suspense>
      </main>
    </div>
  );
}
