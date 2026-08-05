import { USE_MOCK } from './config';
import * as realApi from './users.real';
import * as mockApi from './users.mock';

export const usersApi = USE_MOCK ? mockApi : realApi;

export type {
  UserMeResponse,
  QuotaResponse,
  UserGroupResponse,
  UpdateUserRequest,
} from './users.real';