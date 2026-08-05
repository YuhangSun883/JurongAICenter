// 这里集中放前端 mock 数据，组件 / api 共享使用
// 后端就绪后整文件可删

export interface RoleSeed {
  id: string;
  name: string;
  url: string;
  date: string;
  size: string;
}

export const ROLES: Record<string, RoleSeed[]> = {
  face: [
    { id: 'r1', name: '女子人偶1.jpg', url: 'https://picsum.photos/seed/r1/400', date: '07/24', size: '2160x3840' },
    { id: 'r2', name: '女子人偶2.png', url: 'https://picsum.photos/seed/r2/400', date: '07/24', size: '959x1280' },
    { id: 'r3', name: '女子人偶3.png', url: 'https://picsum.photos/seed/r3/400', date: '07/24', size: '557x1067' },
    { id: 'r4', name: '女子人偶4.png', url: 'https://picsum.photos/seed/r4/400', date: '07/24', size: '1536x2752' },
    { id: 'r5', name: '女子人偶5.png', url: 'https://picsum.photos/seed/r5/400', date: '07/24', size: '1536x2752' },
    { id: 'r6', name: '女子人偶6.png', url: 'https://picsum.photos/seed/r6/400', date: '07/24', size: '768x1376' },
    { id: 'r7', name: '女子人偶7.png', url: 'https://picsum.photos/seed/r7/400', date: '07/24', size: '1536x2752' },
    { id: 'r8', name: '女子人偶8.png', url: 'https://picsum.photos/seed/r8/400', date: '07/24', size: '1024x1536' },
    { id: 'r9', name: '女子人偶9.png', url: 'https://picsum.photos/seed/r9/400', date: '07/24', size: '437x785' },
    { id: 'r10', name: '女子四视图1.png', url: 'https://picsum.photos/seed/r10/400', date: '07/24', size: '1536x2752' },
  ],
  'urban-blue': [],
  'urban-silver': [],
  kids: [],
  mom: [],
  'town-young': [],
  'town-mid': [],
  fantasy: [],
  chinese: [],
  fashion: [],
  animal: [],
};
