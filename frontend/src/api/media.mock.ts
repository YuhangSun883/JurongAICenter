// 媒体模块的 mock
import type {
  MediaItem,
  MediaListQuery,
  MediaUploadResponse,
  RoleCategory,
  RoleListQuery,
} from '@/types/media';
import { ROLES } from '@/components/common/MediaPickerDialog.mocks';

const delay = <T>(v: T, ms = 200) => new Promise<T>((r) => setTimeout(() => r(v), ms));

const ASSETS: MediaItem[] = [
  { id: 'a1', type: 'image', source: 'uploaded', url: 'https://picsum.photos/seed/a1/300', name: '商品-主图-01.png', createdAt: Date.now() },
  { id: 'a2', type: 'image', source: 'uploaded', url: 'https://picsum.photos/seed/a2/300', name: '商品-主图-02.png', createdAt: Date.now() },
  { id: 'a3', type: 'image', source: 'ai-generated', url: 'https://picsum.photos/seed/a3/300', name: 'AI-banner.png', createdAt: Date.now() },
  { id: 'a4', type: 'image', source: 'ai-generated', url: 'https://picsum.photos/seed/a4/300', name: 'AI-主图.png', createdAt: Date.now() },
  { id: 'a5', type: 'image', source: 'ai-generated', url: 'https://picsum.photos/seed/a5/300', name: 'AI-详情页.png', createdAt: Date.now() },
  { id: 'a6', type: 'image', source: 'ai-generated', url: 'https://picsum.photos/seed/a6/300', name: 'AI-场景图.png', createdAt: Date.now() },
  { id: 'a7', type: 'image', source: 'ai-generated', url: 'https://picsum.photos/seed/a7/300', name: 'AI-海报.png', createdAt: Date.now() },
];

export async function listAssets(q: MediaListQuery = {}): Promise<{ items: MediaItem[]; total: number }> {
  let items = ASSETS.slice();
  if (q.category) items = items.filter((a) => a.type === q.category);
  if (q.source && q.source !== 'all') items = items.filter((a) => a.source === q.source);
  if (q.keyword) items = items.filter((a) => a.name.includes(q.keyword!));
  return delay({ items, total: items.length });
}

export async function deleteAsset(id: string): Promise<void> { /* mock */ }

export async function uploadAsset(file: File): Promise<MediaUploadResponse> {
  // 真实场景下这里会调 OSS/S3 直传
  return delay({
    item: {
      id: 'm_' + Math.random().toString(36).slice(2, 8),
      type: file.type.startsWith('video') ? 'video' : file.type.startsWith('audio') ? 'audio' : 'image',
      source: 'uploaded',
      url: URL.createObjectURL(file),
      name: file.name,
      size: file.size,
      createdAt: Date.now(),
    },
  });
}

const ROLE_CATEGORIES: RoleCategory[] = [
  { key: 'face', label: '逼真人脸' },
  { key: 'urban-blue', label: '都市蓝领' },
  { key: 'urban-silver', label: '都市银发' },
  { key: 'kids', label: '儿童' },
  { key: 'mom', label: '精致妈妈' },
  { key: 'town-young', label: '小镇青年' },
  { key: 'town-mid', label: '小镇中老年' },
  { key: 'fantasy', label: '二次元' },
  { key: 'chinese', label: '国风' },
  { key: 'fashion', label: '时尚模特' },
  { key: 'animal', label: '动物' },
];

export async function listRoleCategories(): Promise<RoleCategory[]> {
  return delay(ROLE_CATEGORIES);
}

export async function listRoles(q: RoleListQuery = {}): Promise<{ items: MediaItem[]; total: number }> {
  const list = (q.category && ROLES[q.category]) || [];
  const items: MediaItem[] = list.map((r) => ({
    id: r.id,
    type: 'image',
    source: 'ai-generated',
    url: r.url,
    name: r.name,
    width: parseInt(r.size.split('x')[0], 10),
    height: parseInt(r.size.split('x')[1], 10),
    createdAt: Date.now(),
  }));
  return delay({ items, total: items.length });
}
