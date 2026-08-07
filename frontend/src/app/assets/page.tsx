import { Sidebar } from '@/components/home/Sidebar';
import { AssetsView } from './AssetsView';

export default function Page() {
  return (
    <div className="min-h-screen pl-[72px]">
      <Sidebar />
      <main className="min-h-screen bg-[#f7f7f8]">
        <AssetsView />
      </main>
    </div>
  );
}
