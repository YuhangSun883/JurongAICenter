import { Sidebar } from '@/components/home/Sidebar';
import { Workbench } from '@/components/workbench/Workbench';

export default function Page() {
  return (
    <div className="min-h-screen pl-[72px]">
      <Sidebar />
      <Workbench />
    </div>
  );
}
