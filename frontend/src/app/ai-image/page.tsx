import { Sidebar } from '@/components/home/Sidebar';
import { ImageSidebarBottom, ImageWorkbench } from '@/components/workbench/ImageWorkbench';

export default function Page() {
  return (
    <div className="min-h-screen pl-[72px]">
      <Sidebar bottom={<ImageSidebarBottom />} />
      <ImageWorkbench />
    </div>
  );
}
