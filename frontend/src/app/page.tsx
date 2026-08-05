import { Sidebar } from '@/components/home/Sidebar';
import { SidebarUserMenu } from '@/components/home/SidebarUserMenu';
import { Hero } from '@/components/home/Hero';
import { ScriptCard } from '@/components/home/ScriptCard';
import { ToolGrid } from '@/components/home/ToolGrid';
import { BottomTabs } from '@/components/home/BottomTabs';

export default function HomePage() {
  return (
    <div className="min-h-screen pl-[72px]">
      <Sidebar bottom={<SidebarUserMenu />} />
      <main className="mx-auto w-full max-w-[1200px] px-4 pb-20 pt-6 sm:px-6">
        <Hero />
        <ScriptCard />
        <ToolGrid />
        <BottomTabs />
      </main>
    </div>
  );
}
