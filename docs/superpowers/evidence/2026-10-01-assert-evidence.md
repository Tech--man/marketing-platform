# 测试证据 · 2026-10-01

- HEAD：`f706bfc`（工作树未提交变更 124 个）
- 生成：`./scripts/assert-evidence.sh`（SKIP_MVN=0 REQUIRE_FRESH=0）

| 套件 | 结果 | 计数 |
|---|---|---|
| 后端 mvn test | 通过 | 报告 106 份：Tests 561 / Failures 0 / Errors 0 / Skipped 0 |
| 后端报告新鲜度 | 新鲜（报告新于全部源文件） | — |
| admin-ui vitest | 通过 | passed 49 / failed 0 / suites 21 |
| h5-ui vitest | 通过 | passed 96 / failed 0 / suites 37 |

## 门禁脚本（发布前另跑；前两道已自动落盘 evidence/，后两道产物在各自输出）
- `scripts/lua-contract.sh`（8 个 Lua × 56+ 断言，一次性 Redis）→ `evidence/<date>-lua-contract.log`
- `scripts/check-migrate-chain.sh`（init+迁移链对拍，一次性 MySQL 8）→ `evidence/<date>-check-migrate-chain.log`
- `scripts/check-scripts.sh`（shell 引号门禁）
- `scripts/check-ui-dist.sh`（需先 mvn package）
