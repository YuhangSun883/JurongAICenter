'use client';

import { createContext, useContext, useState, type ReactNode } from 'react';

/** 全局共享素材库（图片/视频/音频），供所有工具页面访问 */
export interface GlobalMaterial {
  id: string;
  type: 'image' | 'video' | 'audio';
  url: string;
  name: string;
  /** 文件大小（字节），用于去重 */
  size?: number;
}

interface MaterialsContextValue {
  materials: GlobalMaterial[];
  addMaterials: (items: GlobalMaterial[]) => void;
  removeMaterial: (id: string) => void;
  clearMaterials: () => void;
  dedupMaterials: () => void;
}

const MaterialsContext = createContext<MaterialsContextValue | null>(null);

export function MaterialsProvider({ children }: { children: ReactNode }) {
  const [materials, setMaterials] = useState<GlobalMaterial[]>([]);

  function addMaterials(items: GlobalMaterial[]) {
    setMaterials((prev) => {
      const ids = new Set(prev.map((x) => x.id));
      const fresh = items.filter((x) => !ids.has(x.id));
      return [...prev, ...fresh];
    });
  }

  function removeMaterial(id: string) {
    setMaterials((prev) => prev.filter((x) => x.id !== id));
  }

  function clearMaterials() {
    setMaterials([]);
  }

  /** 去重素材库：按 name + size 合并同名同 size 的项 */
  function dedupMaterials() {
    setMaterials((prev) => {
      const seen = new Set<string>();
      const result: GlobalMaterial[] = [];
      for (const m of prev) {
        const fp = `${m.name}_${m.size ?? m.url.length}`;
        if (seen.has(fp)) continue;
        seen.add(fp);
        result.push(m);
      }
      return result;
    });
  }

  return (
    <MaterialsContext.Provider value={{ materials, addMaterials, removeMaterial, clearMaterials, dedupMaterials }}>
      {children}
    </MaterialsContext.Provider>
  );
}

export function useMaterials() {
  const ctx = useContext(MaterialsContext);
  if (!ctx) {
    throw new Error('useMaterials must be used within MaterialsProvider');
  }
  return ctx;
}
