'use client';

import { AlertTriangle, X } from 'lucide-react';

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  description?: string;
  /** 确认按钮文案,默认"确认" */
  confirmText?: string;
  /** 取消按钮文案,默认"取消" */
  cancelText?: string;
  /** true 时确认按钮变红,用于危险操作(删除/清空等) */
  danger?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * 通用确认弹窗(替代浏览器原生 confirm/alert)
 *
 * 用法:
 *   <ConfirmDialog
 *     open={!!target}
 *     title="删除对话？"
 *     description="删除后无法恢复"
 *     confirmText="删除"
 *     danger
 *     onConfirm={handleConfirm}
 *     onCancel={() => setTarget(null)}
 *   />
 */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmText = '确认',
  cancelText = '取消',
  danger = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-[100] grid place-items-center bg-slate-900/35 p-4 backdrop-blur-[6px]"
      onClick={onCancel}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-dialog-title"
        onClick={(e) => e.stopPropagation()}
        className="relative w-full max-w-md rounded-[18px] bg-white p-6 shadow-[0_24px_80px_rgba(15,23,42,0.24)]"
      >
        <button
          type="button"
          aria-label="关闭"
          onClick={onCancel}
          className="absolute right-3 top-3 z-10 grid h-8 w-8 place-items-center rounded-full text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
        >
          <X className="h-4 w-4" />
        </button>

        <div className="mb-4 flex items-start gap-3">
          <div
            className={
              'grid h-10 w-10 flex-none place-items-center rounded-full ' +
              (danger ? 'bg-red-50 text-red-500' : 'bg-brand-50 text-brand')
            }
          >
            <AlertTriangle className="h-5 w-5" />
          </div>
          <div className="flex-1 pt-0.5">
            <h2
              id="confirm-dialog-title"
              className="text-base font-semibold leading-6 text-slate-900"
            >
              {title}
            </h2>
            {description && (
              <p className="mt-2 whitespace-pre-line text-sm leading-6 text-slate-500">
                {description}
              </p>
            )}
          </div>
        </div>

        <div className="mt-6 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="h-9 rounded-lg border border-slate-200 bg-white px-4 text-sm font-medium text-slate-700 transition hover:bg-slate-50"
          >
            {cancelText}
          </button>
          <button
            type="button"
            onClick={onConfirm}
            className={
              'h-9 rounded-lg px-4 text-sm font-medium text-white transition ' +
              (danger
                ? 'bg-red-500 hover:bg-red-600'
                : 'bg-slate-900 hover:bg-slate-800')
            }
          >
            {confirmText}
          </button>
        </div>
      </div>
    </div>
  );
}
