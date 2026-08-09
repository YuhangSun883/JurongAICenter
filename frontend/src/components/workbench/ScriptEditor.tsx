'use client';

import { useState, useRef, useEffect } from 'react';
import { Wand2, Sparkles } from 'lucide-react';
import { useWorkbenchStore } from '@/store/workbench';
import { useMaterials, type GlobalMaterial } from '@/contexts/MaterialsContext';
import { AddMaterialCard } from '@/components/common/AddMaterialCard';
import { MediaPickerDialog, type PickedMedia } from '@/components/common/MediaPickerDialog';
import { cn } from '@/lib/utils';

/**
 * AI 视频脚本编辑器：
 * - 顶部缩略图横排 + 添加按钮
 * - 中间 textarea（带 @ 浮层）
 * - 底部 0/10000 + 帮我写
 *
 * 设计原则：能用最少依赖搞定就不要拉 Tiptap/ProseMirror。
 */
export function ScriptEditor() {
  const script = useWorkbenchStore((s) => s.script);
  const setScript = useWorkbenchStore((s) => s.setScript);
  const references = useWorkbenchStore((s) => s.references);
  const addReference = useWorkbenchStore((s) => s.addReference);
  const removeReference = useWorkbenchStore((s) => s.removeReference);
  const { materials, addMaterials, removeMaterial } = useMaterials();

  const taRef = useRef<HTMLTextAreaElement>(null);
  const [showSuggest, setShowSuggest] = useState(false);
  const [query, setQuery] = useState('');
  const [pickerOpen, setPickerOpen] = useState(false);

  const MAX_REFS = 15;

  // 检测 @ 输入
  useEffect(() => {
    const ta = taRef.current;
    if (!ta) return;
    const onSel = () => {
      const pos = ta.selectionStart;
      const before = script.slice(0, pos);
      const m = before.match(/@([\u4e00-\u9fa5\w-]*)$/);
      if (m) {
        setQuery(m[1]);
        setShowSuggest(true);
      } else {
        setShowSuggest(false);
      }
    };
    ta.addEventListener('keyup', onSel);
    ta.addEventListener('click', onSel);
    return () => {
      ta.removeEventListener('keyup', onSel);
      ta.removeEventListener('click', onSel);
    };
  }, [script]);

  const filtered = references.filter(
    (r) => r.token.toLowerCase().includes(query.toLowerCase()) || r.name.includes(query)
  );

  function insertToken(token: string) {
    const ta = taRef.current;
    if (!ta) return;
    const pos = ta.selectionStart;
    const before = script.slice(0, pos);
    const m = before.match(/@([\u4e00-\u9fa5\w-]*)$/);
    if (!m) return;
    const start = pos - m[0].length;
    const next = script.slice(0, start) + '@' + token + ' ' + script.slice(pos);
    setScript(next);
    setShowSuggest(false);
    requestAnimationFrame(() => {
      ta.focus();
      const np = start + token.length + 2;
      ta.setSelectionRange(np, np);
    });
  }

  return (
    <div className="card relative p-4">
      {/* 第一行：左侧 0/15 + 右侧 图标按钮组（参考图右上角） */}
      <div className="mb-3 flex items-center justify-between">
        <span className="text-xs text-fg-subtle">
          {references.length} / {MAX_REFS}
        </span>
        <div className="flex items-center gap-1">
          <button
            type="button"
            className="grid h-7 w-7 place-items-center rounded-md text-fg-muted hover:bg-bg-soft hover:text-fg"
            aria-label="AI 助手"
            title="AI 助手"
          >
            <Sparkles className="h-3.5 w-3.5" />
          </button>
          <button
            type="button"
            className="grid h-7 w-7 place-items-center rounded-md text-fg-muted hover:bg-bg-soft hover:text-fg"
            aria-label="列表视图"
            title="列表视图"
          >
            <span className="text-base leading-none">≡</span>
          </button>
          <button
            type="button"
            className="grid h-7 w-7 place-items-center rounded-md text-fg-muted hover:bg-bg-soft hover:text-fg"
            aria-label="全屏"
            title="全屏"
          >
            <span className="text-base leading-none">⤢</span>
          </button>
        </div>
      </div>

      {/* 缩略图横排 + + 添加按钮（参考图样式） */}
      <div className="flex flex-wrap items-center gap-2.5 pb-3">
        {references.map((r) => (
          <div
            key={r.id}
            className="group/ref relative h-20 w-20 flex-none overflow-hidden rounded-xl border border-bg-line bg-bg-soft"
          >
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={r.url}
              alt={r.name}
              className="h-full w-full object-cover"
            />
            <button
              type="button"
              onClick={() => removeReference(r.id)}
              className="absolute right-1 top-1 grid h-5 w-5 place-items-center rounded-full bg-black/60 text-[14px] leading-none text-white opacity-0 transition group-hover/ref:opacity-100"
              aria-label={`移除 ${r.name}`}
            >
              ×
            </button>
            <span className="absolute bottom-0 left-0 right-0 truncate bg-black/40 px-1 py-0.5 text-[10px] text-white">
              @{r.token}
            </span>
          </div>
        ))}
        {references.length < MAX_REFS && (
          <AddMaterialCard
            onClick={() => setPickerOpen(true)}
            label="点击添加"
            className="h-20 w-20 flex-none rounded-xl"
            iconClassName="h-5 w-5"
            labelClassName="mt-1 text-[10px]"
          />
        )}
      </div>

      {/* 分割线 + 提示文字 */}
      <div className="border-t border-bg-line/60 pt-3 text-xs text-fg-muted">
        输入视频脚本，使用 <span className="text-fg-subtle">@</span> 指定参考素材，或
        <button
          type="button"
          onClick={() => setScript('一只在霓虹城市奔跑的机器猫，电影感镜头，慢动作 120fps。')}
          className="ml-1 text-brand hover:underline"
        >
          「帮我写」
        </button>
      </div>

      {/* 文本输入区 */}
      <div className="relative mt-3">
        <textarea
          ref={taRef}
          value={script}
          onChange={(e) => setScript(e.target.value)}
          placeholder="描述你想要的画面…例如：赛博朋克街道，雨水反射霓虹，一个穿风衣的女孩回头。"
          maxLength={10000}
          className={cn(
            'block min-h-[260px] w-full resize-y rounded-xl border border-bg-line bg-bg-soft/60 p-3 pb-7 text-sm leading-6 text-fg outline-none',
            'placeholder:text-fg-subtle focus:border-brand/60 focus:ring-2 focus:ring-brand/20'
          )}
        />
        <div className="pointer-events-none absolute bottom-2 left-3 text-[11px] text-fg-subtle">
          {script.length} / 10000
        </div>
        <div className="absolute bottom-2 right-3 flex items-center gap-1 text-[11px] text-brand">
          <Wand2 className="h-3 w-3" />
          <button
            type="button"
            onClick={() => setScript('一只在霓虹城市奔跑的机器猫，电影感镜头，慢动作 120fps。')}
          >
            帮我写
          </button>
        </div>

        {showSuggest && filtered.length > 0 && (
          <div className="absolute z-10 mt-1 max-h-56 w-72 overflow-auto rounded-xl border border-bg-line bg-bg-card/95 p-1 shadow-2xl">
            {filtered.map((r) => (
              <button
                key={r.id}
                onClick={() => insertToken(r.token)}
                className="flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-sm text-fg-muted hover:bg-bg-soft hover:text-fg"
              >
                <span className="chip">{r.type}</span>
                <span className="truncate">@{r.token}</span>
                <span className="ml-auto truncate text-[11px] text-fg-subtle">{r.name}</span>
              </button>
            ))}
          </div>
        )}
      </div>

      {/* 弹窗选择素材 */}
      <MediaPickerDialog
        open={pickerOpen}
        onClose={() => setPickerOpen(false)}
        uploadedFiles={materials}
        showMockAssets={false}
        max={MAX_REFS - references.length}
        onUploadFiles={(files): PickedMedia[] => {
          if (!files) return [];
          const arr = Array.from(files);
          const existFp = new Set(materials.map((m) => `${m.name}_${m.size ?? m.url.length}`));
          const fresh: GlobalMaterial[] = [];
          for (const f of arr) {
            const fp = `${f.name}_${f.size}`;
            if (existFp.has(fp)) continue;
            const dup = fresh.find((x) => `${x.name}_${f.size}` === fp);
            if (dup) continue;
            existFp.add(fp);
            fresh.push({
              id: `up_${Date.now()}_${f.name}_${Math.random().toString(36).slice(2, 8)}`,
              type: (f.type.startsWith('video') ? 'video' : f.type.startsWith('audio') ? 'audio' : 'image') as 'image' | 'video' | 'audio',
              url: URL.createObjectURL(f),
              name: f.name,
              size: f.size,
            });
          }
          addMaterials(fresh);
          return fresh;
        }}
        onRemoveUploaded={(id) => removeMaterial(id)}
        onConfirm={(arr) => {
          for (const m of arr) {
            addReference({
              id: m.id,
              token: m.name.replace(/\.[^/.]+$/, '').slice(0, 8) + '_' + Math.random().toString(36).slice(2, 5),
              name: m.name,
              type: m.type,
              url: m.url,
            });
          }
          setPickerOpen(false);
        }}
      />
    </div>
  );
}
