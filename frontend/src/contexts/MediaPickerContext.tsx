'use client';

import { createContext, useContext, useState, type ReactNode } from 'react';
import { MediaPickerDialog, type PickedMedia } from '@/components/common/MediaPickerDialog';
import { useMaterials, type GlobalMaterial } from './MaterialsContext';

export type MediaPickerTab = '图片' | '视频' | '音频';

export interface OpenMediaPickerOptions {
  initialTab?: MediaPickerTab;
  max?: number;
  title?: string;
  subtitle?: string;
  accept?: string;
  showMockAssets?: boolean;
  onConfirm?: (picked: PickedMedia[]) => void;
}

interface MediaPickerContextValue {
  openMediaPicker: (options?: OpenMediaPickerOptions) => void;
  closeMediaPicker: () => void;
}

const MediaPickerContext = createContext<MediaPickerContextValue | null>(null);

export function MediaPickerProvider({ children }: { children: ReactNode }) {
  const { materials, addMaterials, removeMaterial } = useMaterials();
  const [open, setOpen] = useState(false);
  const [options, setOptions] = useState<OpenMediaPickerOptions>({});

  function openMediaPicker(nextOptions: OpenMediaPickerOptions = {}) {
    setOptions(nextOptions);
    setOpen(true);
  }

  function closeMediaPicker() {
    setOpen(false);
  }

  function handleUploadFiles(files: FileList | null): PickedMedia[] {
    if (!files) return [];

    const items: GlobalMaterial[] = Array.from(files).map((file, index) => ({
      id: `upload_${Date.now()}_${index}_${Math.random().toString(36).slice(2, 7)}`,
      type: file.type.startsWith('video')
        ? 'video'
        : file.type.startsWith('audio')
          ? 'audio'
          : 'image',
      url: URL.createObjectURL(file),
      name: file.name,
      size: file.size,
    }));

    addMaterials(items);
    return items;
  }

  function handleRemoveUploaded(id: string) {
    const item = materials.find((material) => material.id === id);
    if (item?.url.startsWith('blob:')) URL.revokeObjectURL(item.url);
    removeMaterial(id);
  }

  return (
    <MediaPickerContext.Provider value={{ openMediaPicker, closeMediaPicker }}>
      {children}
      <MediaPickerDialog
        open={open}
        onClose={closeMediaPicker}
        onConfirm={(picked) => {
          options.onConfirm?.(picked);
          closeMediaPicker();
        }}
        onUploadFiles={handleUploadFiles}
        onRemoveUploaded={handleRemoveUploaded}
        uploadedFiles={materials}
        max={options.max ?? 12}
        initialTab={options.initialTab ?? '图片'}
        title={options.title}
        subtitle={options.subtitle}
        accept={options.accept}
        showMockAssets={options.showMockAssets ?? false}
      />
    </MediaPickerContext.Provider>
  );
}

export function useMediaPicker() {
  const context = useContext(MediaPickerContext);
  if (!context) {
    throw new Error('useMediaPicker must be used within MediaPickerProvider');
  }
  return context;
}
