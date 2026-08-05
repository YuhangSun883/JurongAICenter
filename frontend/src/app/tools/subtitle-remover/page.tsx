import { Sidebar } from '@/components/home/Sidebar';
import { PlaceholderPage } from '@/components/common/PlaceholderPage';

export default function Page() {
  return (
    <div className="min-h-screen pl-[72px]">
      <Sidebar />
      <main className="mx-auto w-full max-w-[1200px] px-4 py-6 sm:px-6">
        <PlaceholderPage title="字幕擦除" desc="一键去除视频字幕与画面文字" emoji="🅰️" />
      </main>
    </div>
  );
}
