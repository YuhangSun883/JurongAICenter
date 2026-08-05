// Agent 业务侧统一入口
import { USE_MOCK } from './config';
import * as real from './agent.real';
import * as mock from './agent.mock';

export const agentApi = {
  listSessions: (q?: Parameters<typeof real.listSessions>[0]) =>
    USE_MOCK ? mock.listSessions(q) : real.listSessions(q),
  createSession: (req?: Parameters<typeof real.createSession>[0]) =>
    USE_MOCK ? mock.createSession(req) : real.createSession(req),
  renameSession: (req: Parameters<typeof real.renameSession>[0]) =>
    USE_MOCK ? mock.renameSession(req) : real.renameSession(req),
  deleteSession: (id: string) =>
    USE_MOCK ? mock.deleteSession(id) : real.deleteSession(id),
  listMessages: (q: Parameters<typeof real.listMessages>[0]) =>
    USE_MOCK ? mock.listMessages(q) : real.listMessages(q),
  send: (req: Parameters<typeof real.send>[0]) =>
    USE_MOCK ? mock.send(req) : real.send(req),
  sendStream: (req: Parameters<typeof real.sendStream>[0]) =>
    USE_MOCK ? mock.sendStream(req) : real.sendStream(req),
  getCredits: () =>
    USE_MOCK ? mock.getCredits() : real.getCredits(),
  // 积分前置校验
  checkCredits: (req: Parameters<typeof real.checkCredits>[0]) =>
    USE_MOCK ? mock.checkCredits(req) : real.checkCredits(req),
  // 套餐 / 订单
  listPlans: () =>
    USE_MOCK ? mock.listPlans() : real.listPlans(),
  createPlanOrder: (req: Parameters<typeof real.createPlanOrder>[0]) =>
    USE_MOCK ? mock.createPlanOrder(req) : real.createPlanOrder(req),
  queryOrder: (orderId: string) =>
    USE_MOCK ? mock.queryOrder(orderId) : real.queryOrder(orderId),
  cancelOrder: (orderId: string) =>
    USE_MOCK ? mock.cancelOrder(orderId) : real.cancelOrder(orderId),
  // 客服联系方式（企业套餐 / 通用客服入口）
  getContactInfo: (scope?: Parameters<typeof real.getContactInfo>[0]) =>
    USE_MOCK ? mock.getContactInfo(scope) : real.getContactInfo(scope),
  // 购买积分（一次性充值）
  listCreditPackages: () =>
    USE_MOCK ? mock.listCreditPackages() : real.listCreditPackages(),
  createCreditsOrder: (req: Parameters<typeof real.createCreditsOrder>[0]) =>
    USE_MOCK ? mock.createCreditsOrder(req) : real.createCreditsOrder(req),
  // 兑换充值卡
  redeemCard: (req: Parameters<typeof real.redeemCard>[0]) =>
    USE_MOCK ? mock.redeemCard(req) : real.redeemCard(req),
};
