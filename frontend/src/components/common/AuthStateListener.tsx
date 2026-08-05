'use client';

import { useEffect } from 'react';

/**
 * 全局监听 auth-changed 事件。
 * 触发场景：
 *   - 注册成功 / 登录成功 / 登出 / refresh 成功 / refresh 失败
 * 作用：
 *   - 刷新页面状态（如顶栏用户信息、积分余额等）
 *
 * 当前为占位组件，未来可在此处放订阅全局状态的副作用。
 */
export function AuthStateListener() {
  useEffect(() => {
    const handler = () => {
      // 触发自定义事件，通知订阅方刷新（业务组件可监听此事件）
      // 这里只做占位，UI 刷新由各业务组件自行订阅 router.refresh() 或重新 fetch 数据
      // eslint-disable-next-line no-console
      console.log('[auth] state changed');
    };
    window.addEventListener('auth-changed', handler);
    return () => window.removeEventListener('auth-changed', handler);
  }, []);

  return null;
}