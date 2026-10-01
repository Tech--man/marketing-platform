# marketing-web：前后端分离（2026-10-01）后的静态承载容器
# 多阶段构建——全仓第一个 node 构建阶段先例：宿主机不需要 node，docker build 内置
# npm ci + vite build；两份源码（admin-ui / h5-ui）在仓，产物只进镜像不进仓库。
# 构建上下文必须是仓库根（COPY 两个 ui 目录 + nginx.conf），compose 里已配好。

# ---------- 阶段 1：后台界面（admin-ui → /ui/） ----------
FROM node:20-alpine AS build-ui
WORKDIR /src
COPY marketing-admin-ui/package.json marketing-admin-ui/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY marketing-admin-ui/ ./
# base=/ui/ 已在 vite.config 固定；产物输出 dist/（vite 默认）
RUN npm run build

# ---------- 阶段 2：C 端 H5（h5-ui → /h5/） ----------
FROM node:20-alpine AS build-h5
WORKDIR /src
COPY marketing-h5-ui/package.json marketing-h5-ui/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY marketing-h5-ui/ ./
RUN npm run build

# ---------- 运行段：nginx ----------
FROM nginx:1.27-alpine
RUN rm /etc/nginx/conf.d/default.conf
COPY marketing-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build-ui /src/dist /usr/share/nginx/html/ui
COPY --from=build-h5 /src/dist /usr/share/nginx/html/h5
EXPOSE 80
# nginx 官方镜像 master 以 root 起再 drop 到 worker——与 JRE 镜像的自建 app 用户不同，
# 这是基础镜像的既定安全模型（worker 进程非 root），维持不动。
