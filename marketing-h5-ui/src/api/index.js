import { api, refreshSession } from "./client";

/**
 * C 端全部端点形状到函数的一一映射。视图/Store 只调这里，不裸写 URL——
 * 路径口径变了只有一个改动点。
 *
 * 消费者账号体系（P1+P2）上线后的硬口径：**请求里不再出现 userId**。
 * 身份由 access token 经网关验签后注入（X-User-Token → 业务侧再验一次），
 * 任何"我自己报一个 userId"的写法都是越权入口，不是便利参数。
 *
 * 游客可看（不需要登录，见网关 consumer.permit-paths）：
 *   activityApi.detail / participatable / budgetRemain、couponApi.stock、
 *   seckillApi.sessions / bucketStock，以及 authApi 的 login/register/refresh。
 * 其余一律需要 access token——包括 grantResult 与 grabResult 这两个"结果轮询"口，
 * 它们回的是属于某个消费者的数据。
 */

/* ---------- 账号 /api/auth ---------- */
export const authApi = {
  register: ({ identifier, password, nickname }) =>
    api.post("/api/auth/register", { identifier, password, nickname }), // A1 → TokenPair
  login: ({ identifier, password }) => api.post("/api/auth/login", { identifier, password }), // A2 → TokenPair
  /** 刷新不带 access token：client.js 的 40101 单飞刷新走的是同一个实现 */
  refresh: (refreshToken) => refreshSession(refreshToken), // A3 → TokenPair
  me: () => api.get("/api/auth/me"), // A4 → {uid, identifier, nickname, status}
  sessions: () => api.get("/api/auth/sessions"), // A5 → 会话列表（含 current 标记）
  logout: () => api.post("/api/auth/logout"), // A6 → 吊销当前会话
  changePassword: ({ oldPassword, newPassword }) =>
    api.put("/api/auth/password", { oldPassword, newPassword }), // A7 → 整号作废旧会话
};

/* ---------- 活动 /api/activity ---------- */
export const activityApi = {
  detail: (no) => api.get(`/api/activity/${encodeURIComponent(no)}`), // 1
  participatable: (no) => api.get(`/api/activity/${encodeURIComponent(no)}/participatable`), // 2
  grayHit: (no) => api.get(`/api/activity/${encodeURIComponent(no)}/gray-hit`), // 3 · 需登录，无查询参数
  deduct: (no, { amountCents, bizKey }) =>
    api.post(`/api/activity/${encodeURIComponent(no)}/budget/deduct`, { amountCents, bizKey }), // 4
  budgetRemain: (no) => api.get(`/api/activity/${encodeURIComponent(no)}/budget/remain`), // 5
};

/* ---------- 券 /api/coupon ---------- */
export const couponApi = {
  grant: ({ requestId, templateNo }) => api.post("/api/coupon/grant", { requestId, templateNo }), // 6
  grantResult: (requestId) => api.get(`/api/coupon/grant/result/${encodeURIComponent(requestId)}`), // 7
  consume: ({ couponCode, orderNo }) => api.post("/api/coupon/consume", { couponCode, orderNo }), // 8
  usable: () => api.get("/api/coupon/usable"), // 9 · 无查询参数
  stock: (templateNo) => api.get(`/api/coupon/stock/${encodeURIComponent(templateNo)}`), // 10
};

/* ---------- 优惠计算 /api/discount ---------- */
export const discountApi = {
  /** CalcInput 里不许出现 userId：服务端 @JsonIgnore 掉了它，再从验签身份回填 */
  calculate: (input) => api.post("/api/discount/calculate", input), // 11
};

/* ---------- 秒杀 /api/seckill ---------- */
export const seckillApi = {
  grab: ({ activityNo }) => api.post("/api/seckill/grab", { activityNo }), // 12
  grabResult: (token) => api.get(`/api/seckill/grab/result/${encodeURIComponent(token)}`), // 13
  sessions: () => api.get("/api/seckill/activities"), // 14
  bucketStock: (activityNo) => api.get(`/api/seckill/stock/${encodeURIComponent(activityNo)}`), // 15
  /** 支付只认本人订单，越权 40300 */
  pay: (orderNo) => api.post(`/api/seckill/pay/${encodeURIComponent(orderNo)}`), // 16
};
