// 提示词持久化存储 —— localStorage 读写
// 用于「保存当前提示词」和「我的提示词」功能

export interface SavedPrompt {
  id: string;
  name: string;
  script: string;
  createdAt: number;
}

const STORAGE_KEY = 'jrai_saved_prompts';

function readAll(): SavedPrompt[] {
  if (typeof window === 'undefined') return [];
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const arr = JSON.parse(raw);
    if (!Array.isArray(arr)) return [];
    return arr;
  } catch {
    return [];
  }
}

function writeAll(prompts: SavedPrompt[]): void {
  if (typeof window === 'undefined') return;
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(prompts));
  } catch {
    // storage full or private browsing — silently fail
  }
}

export function listPrompts(): SavedPrompt[] {
  return readAll().sort((a, b) => b.createdAt - a.createdAt);
}

export function savePrompt(name: string, script: string): SavedPrompt {
  const prompts = readAll();
  const prompt: SavedPrompt = {
    id: `prompt_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
    name: name.trim(),
    script,
    createdAt: Date.now(),
  };
  prompts.push(prompt);
  writeAll(prompts);
  return prompt;
}

export function deletePrompt(id: string): void {
  const prompts = readAll().filter((p) => p.id !== id);
  writeAll(prompts);
}
