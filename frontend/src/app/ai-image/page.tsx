import { Sidebar } from '@/components/home/Sidebar';
import { ImageWorkbench } from '@/components/workbench/ImageWorkbench';
import { LoginGate } from '@/components/common/LoginGate';

export default function Page() {
  return (
    <LoginGate>
      <div className="min-h-screen pl-[72px]">
        <Sidebar />
        <ImageWorkbench />
      </div>
    </LoginGate>
  );
}
