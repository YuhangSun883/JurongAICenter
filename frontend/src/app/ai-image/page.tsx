import { Sidebar } from '@/components/home/Sidebar';
import { ImageSidebarBottom, ImageWorkbench } from '@/components/workbench/ImageWorkbench';
import { LoginGate } from '@/components/common/LoginGate';

export default function Page() {
  return (
    <LoginGate>
      <div className="min-h-screen pl-[72px]">
        <Sidebar bottom={<ImageSidebarBottom />} />
        <ImageWorkbench />
      </div>
    </LoginGate>
  );
}
