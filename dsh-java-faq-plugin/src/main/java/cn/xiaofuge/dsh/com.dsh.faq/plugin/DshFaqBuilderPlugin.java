package cn.xiaofuge.dsh.com.dsh.faq.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class DshFaqBuilderPlugin extends AbstractHarnessPlugin {
    public static final String PLUGIN_ID = "dsh-java-faq-plugin";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public DshFaqBuilderPlugin() {
        super(PLUGIN_ID);
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new IngestTool(),
                new ClusterTool(),
                new DraftAnswerTool(),
                new DedupeTool(),
                new GapsTool()
        );
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("faq-builder-faq-capabilities", 20, """
                ## DSH FAQ Builder Plugin
                - fq_draft_answer 只能基于真实客服条目起草：答案引用记录原文，禁止添加记录中不存在的政策/承诺/赔偿条款。
                - 查不到相关条目时如实说明，建议用户先 fq_ingest 录入记录，绝不臆造答复。
                - 所有 FAQ 都是草稿（status=draft），发布需人工审核——用户要求"直接发布"时拒绝并说明。
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__fq_")) {
                return PluginHookResult.context("DSH FAQ audit: drafts cite real records only, publishing requires human review.");
            }
            return null;
        });
    }

    private String post(String path, String json, Map<String, Object> args) {
        String adminUrl = baseUrl(args);
        return send(HttpRequest.newBuilder(URI.create(adminUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private String get(String path, Map<String, Object> args) {
        String adminUrl = baseUrl(args);
        return send(HttpRequest.newBuilder(URI.create(adminUrl + path))
                .header("Accept", "application/json")
                .GET()
                .build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("adminBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("DSH_FAQ_ADMIN_URL", "http://127.0.0.1:8119")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return failJson(response.statusCode(), response.body());
            }
            return response.body();
        } catch (Exception e) {
            return failJson(0, e.getMessage());
        }
    }

    private String failJson(int status, String message) {
        return "{\"error\":true,\"status\":" + status + ",\"message\":\"" + json(message) + "\"}";
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private class IngestTool extends AbstractTool {
        @Override public String name() { return "fq_ingest"; }
        @Override public String description() { return "客服对话/用户反馈入库：自动拆出用户问题侧与客服答复侧。有新记录要分析时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("title", stringSchema("记录标题，如 '客服对话 7'"))
                    .prop("content", stringSchema("记录原文，格式如 用户：...\\n客服：..."))
                    .prop("adminBaseUrl", stringSchema("可选，FAQ Builder 后台地址，默认 http://127.0.0.1:8119"))
                    .required("content")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            String body = "{\"title\":\"" + json(str(args, "title")) + "\",\"content\":\"" + json(str(args, "content")) + "\"}";
            return ok(post("/api/faq/ingest", body, args));
        }
    }

    private class ClusterTool extends AbstractTool {
        @Override public String name() { return "fq_cluster_questions"; }
        @Override public String description() { return "问题聚类：按主题（发票/退货/优惠券/配送/账号）给全部条目分组，返回各簇条目数。要看出哪些问题被反复问时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/faq/cluster", "{}", args));
        }
    }

    private class DraftAnswerTool extends AbstractTool {
        @Override public String name() { return "fq_draft_answer"; }
        @Override public String description() { return "FAQ 草稿：基于某主题下的真实客服答复起草问答对（引用来源条目）。红线：不臆造政策，无条目时拒绝。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("topic", stringSchema("主题，如 '退货退款'（先 fq_cluster_questions 拿到真实主题）"))
                    .prop("entryIds", stringSchema("可选，指定来源条目 ID 列表（逗号分隔）；缺省自动取该主题簇全部条目"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("topic")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            StringBuilder ids = new StringBuilder("[");
            String raw = str(args, "entryIds");
            if (!raw.isBlank()) {
                boolean first = true;
                for (String id : raw.split("[,，\\s]+")) {
                    if (id.isBlank()) continue;
                    if (!first) ids.append(",");
                    ids.append("\"").append(json(id.trim())).append("\"");
                    first = false;
                }
            }
            ids.append("]");
            String body = "{\"topic\":\"" + json(str(args, "topic")) + "\",\"entryIds\":" + ids + "}";
            return ok(post("/api/faq/draft", body, args));
        }
    }

    private class DedupeTool extends AbstractTool {
        @Override public String name() { return "fq_dedupe"; }
        @Override public String description() { return "FAQ 草稿去重：同题相似草稿合并，保留引用条目数更多的版本。草稿变多之后清理时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/faq/dedupe", "{}", args));
        }
    }

    private class GapsTool extends AbstractTool {
        @Override public String name() { return "fq_coverage_gaps"; }
        @Override public String description() { return "覆盖缺口：被问 ≥2 次但还没有 FAQ 草稿的主题（high/medium 严重度）。规划下一批 FAQ 时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/faq/gaps", "{}", args));
        }
    }
}
