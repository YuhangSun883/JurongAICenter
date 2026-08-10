// 收藏视频持久化存储

export interface FavoriteVideo {
  id: string;
  taskId: string;
  resultUrl: string;
  thumbnailUrl?: string;
  script: string;
  model: string;
  duration: number;
  createdAt: number;
}

const STORAGE_KEY = 'jrai_favorite_videos';

function readAll(): FavoriteVideo[] {
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

function writeAll(items: FavoriteVideo[]): void {
  if (typeof window === 'undefined') return;
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
  } catch { /* storage full */ }
}

export function listFavorites(): FavoriteVideo[] {
  return readAll().sort((a, b) => b.createdAt - a.createdAt);
}

export function addFavorite(video: FavoriteVideo): void {
  const items = readAll();
  if (items.some((v) => v.taskId === video.taskId)) return;
  items.push(video);
  writeAll(items);
}

export function removeFavorite(taskId: string): void {
  writeAll(readAll().filter((v) => v.taskId !== taskId));
}

export function isFavorited(taskId: string): boolean {
  return readAll().some((v) => v.taskId === taskId);
}
