'use client';

import { useRef } from 'react';
import { Plus } from 'lucide-react';
import { cn } from '@/lib/utils';

interface AddMaterialCardProps {
  label?: string;
  hint?: string;
  title?: string;
  className?: string;
  iconClassName?: string;
  labelClassName?: string;
  hintClassName?: string;
  disabled?: boolean;
  onClick?: () => void;
  onUploadFiles?: (files: FileList | null) => void;
  accept?: string;
  multiple?: boolean;
  iconOnly?: boolean;
}

export function AddMaterialCard({
  label = '添加素材',
  hint,
  title,
  className,
  iconClassName,
  labelClassName,
  hintClassName,
  disabled,
  onClick,
  onUploadFiles,
  accept = 'image/*,video/*,audio/*',
  multiple = true,
  iconOnly = false,
}: AddMaterialCardProps) {
  const inputRef = useRef<HTMLInputElement>(null);

  return (
    <button
      type="button"
      title={title}
      disabled={disabled}
      onClick={() => {
        onClick?.();
        if (onUploadFiles && inputRef.current) {
          inputRef.current.value = '';
          inputRef.current.click();
        }
      }}
      className={cn(
        'group flex aspect-square flex-col items-center justify-center rounded-2xl border border-dashed border-[#dfe6f4] bg-[#f8faff] p-2 text-[#8a96aa] transition hover:border-[#9aabff] hover:bg-white hover:text-[#5876ff] disabled:cursor-not-allowed disabled:opacity-45',
        className
      )}
    >
      <Plus
        className={cn(
          'h-9 w-9 rotate-12 stroke-[2.5] transition-transform group-hover:rotate-0',
          iconClassName
        )}
      />
      {!iconOnly && (
        <>
          <span className={cn('mt-1.5 text-[11px] font-medium', labelClassName)}>{label}</span>
          {hint && <span className={cn('mt-0.5 text-[10px] text-[#9aa5b8]', hintClassName)}>{hint}</span>}
        </>
      )}
      {onUploadFiles && (
        <input
          ref={inputRef}
          type="file"
          multiple={multiple}
          accept={accept}
          className="hidden"
          onClick={(event) => event.stopPropagation()}
          onChange={(event) => {
            const files = event.target.files;
            if (files && files.length > 0) onUploadFiles(files);
            event.currentTarget.value = '';
          }}
        />
      )}
    </button>
  );
}
