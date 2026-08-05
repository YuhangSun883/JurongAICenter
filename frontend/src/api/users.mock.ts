import type {
  UserMeResponse,
  QuotaResponse,
  UserGroupResponse,
  UpdateUserRequest,
} from './users.real';

function delay<T>(value: T, ms = 200): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms));
}

const MOCK_USER: UserMeResponse = {
  id: 1,
  email: 'rootadmin@jurong.local',
  displayName: 'rootadmin',
  role: 'ADMIN',
  credits: 50,
  monthlyQuota: 100,
  quotaUsed: 50,
  plan: 'ENTERPRISE',
};

const MOCK_QUOTA: QuotaResponse = {
  credits: 50,
  monthlyQuota: 100,
  quotaUsed: 50,
  plan: 'ENTERPRISE',
};

const MOCK_GROUPS: UserGroupResponse[] = [
  { id: 1, name: 'Default', description: '默认分组', color: '#6366f1' },
];

export async function getMe(): Promise<UserMeResponse> {
  return delay({ ...MOCK_USER });
}

export async function updateMe(body: UpdateUserRequest): Promise<UserMeResponse> {
  const updated = { ...MOCK_USER };
  if (body.displayName) updated.displayName = body.displayName;
  return delay(updated);
}

export async function getMyQuota(): Promise<QuotaResponse> {
  return delay({ ...MOCK_QUOTA });
}

export async function getMyGroups(): Promise<UserGroupResponse[]> {
  return delay([...MOCK_GROUPS]);
}