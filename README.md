# DSH FAQ Builder（FAQ 建造器）

> 独立 AI 产品案例 · deepseek-harness-java Java Native Plugin
> 记录入库 · 问题聚类 · 草稿起草 · 去重 · 覆盖缺口（草稿需人工审核）

一个基于 DSH（deepseek-harness-java）Java Native Plugin 的独立 AI 产品：把客服对话/用户反馈变成结构化 FAQ。草稿答案必须引用真实客服记录原文，**红线是不臆造政策答复、不直接发布**。

## 架构

```
┌─────────────────────────────┐        ┌──────────────────────────────┐
│  dsh-java-faq-app           │        │  deepseek-harness-java:8090  │
│  (Spring Boot, port 8119)   │        │                              │
│  ├─ REST: /api/faq/*        │◄───────┤  DshFaqBuilderPlugin         │
│  ├─ /api/assistant/stream ──┼───────►│  (JAVA_NATIVE, in-process)   │
│  └─ static/ FAQ 工作台 UI    │  SSE   │   ├─ fq_ingest               │
└─────────────────────────────┘        │   ├─ fq_cluster_questions    │
                                       │   ├─ fq_draft_answer         │
                                       │   ├─ fq_dedupe               │
                                       │   └─ fq_coverage_gaps        │
                                       └──────────────────────────────┘
```

- **App**（`dsh-java-faq-app`）：Spring Boot 应用，提供 FAQ REST API + 工作台前端 + SSE 助手转发。
- **Plugin**（`dsh-java-faq-plugin`）：`AbstractHarnessPlugin` Java Native 插件，5 个工具通过 REST 回调 App。

## 核心功能

| 工具 | 说明 |
|------|------|
| `fq_ingest` | 客服对话/反馈入库：自动拆分用户问题侧与客服答复侧 |
| `fq_cluster_questions` | 问题聚类：按主题关键词（发票/退货/优惠券/配送/账号）分组，返回各簇条目数 |
| `fq_draft_answer` | FAQ 草稿：基于该主题下真实客服答复起草问答对（附来源条目原文）；无条目时拒绝（不臆造） |
| `fq_dedupe` | 草稿去重：同题相似（Jaccard≥0.4）合并，保留引用条目数（basedOnCount）更多的版本 |
| `fq_coverage_gaps` | 覆盖缺口：被问 ≥2 次但还没有草稿的主题（high/medium 严重度） |

## 安全红线

- **不臆造政策答复**：草稿答案只引用真实客服记录原文，不添加记录中不存在的政策/承诺/赔偿条款；没有条目时工具直接报错。
- **不直接发布**：所有 FAQ 停留在 status=draft，发布需人工审核——用户要求"直接发布"时 AI 拒绝并说明流程。
- systemPrompt + PRE_TOOL_USE hook 强制约束；E2E T7/T8 分别验证拒绝发布与只引用真实政策（7 天，不顺着用户说 30 天）。

## 运行

```bash
# 1. 构建
mvn package -DskipTests

# 2. 启动 App（端口 8119）
SERVER__PORT= java -jar dsh-java-faq-app/target/dsh-java-faq-app-0.1.0-SNAPSHOT.jar --server.port=8119

# 3. 安装插件（harness 运行在 8090）
cp dsh-java-faq-plugin/target/dsh-java-faq-plugin-0.1.0-SNAPSHOT.jar ~/.dsh/standalone/plugins/dsh-java-faq-plugin.jar
curl -X POST http://127.0.0.1:8090/api/harness/plugins/install -H 'Content-Type: application/json' -d '{
  "pluginId":"dsh-java-faq-plugin",
  "jarName":"dsh-java-faq-plugin.jar",
  "displayName":"FAQ Builder",
  "pluginVersion":"0.1.0",
  "runtimeType":"JAVA_NATIVE",
  "sourcePath":"/Users/<you>/.dsh/standalone/plugins/dsh-java-faq-plugin.jar",
  "entrypoint":"cn.xiaofuge.dsh.com.dsh.faq.plugin.DshFaqBuilderPlugin",
  "installMode":"REPLACE"
}'
curl -X POST http://127.0.0.1:8090/api/harness/plugins/activate -H 'Content-Type: application/json' -d '{"pluginId":"dsh-java-faq-plugin"}'

# 4. 打开 http://127.0.0.1:8119
```

> 注意：替换插件 jar 后需要重启 harness 进程，否则 JVM 缓存旧 jar 句柄会报 `ZipFile invalid LOC header`。

## E2E

```bash
python3 faq_builder_e2e.py   # 25/25 PASS
```

覆盖：入库/短内容拒绝、聚类（退货/发票簇）、草稿起草（真实引用 + basedOnCount + 不臆造拒绝）、去重、覆盖缺口、SSE AI 聚类+起草、红线（拒绝直接发布、不顺着用户编 30 天政策）。

## 截图

| 文件 | 场景 |
|------|------|
| `screenshots/01-home.png` | 工作台首页：聚类/草稿/缺口三面板 |
| `screenshots/02-ai-drawer.png` | AI 聚类后起草 FAQ，展示引用的 2 条真实记录 |
| `screenshots/03-safety.png` | AI 拒绝"跳过审核直接发布"，说明需人工确认 |
