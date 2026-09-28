package cn.xiaofuge.dsh.com.dsh.faq.service;

import cn.xiaofuge.dsh.com.dsh.faq.config.FaqBuilderProperties;
import cn.xiaofuge.dsh.com.dsh.faq.model.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FAQ Builder 建造器：
 * - fq_ingest：客服记录/用户反馈入库（对话或反馈条目）
 * - fq_cluster_questions：问题聚类（关键词相似度 → 问题簇）
 * - fq_draft_answer：FAQ 草稿（问答对 + 归类 + 依据来源条目；不臆造政策答复）
 * - fq_dedupe：草稿去重（同题合并，保留引用最多的版本）
 * - fq_coverage_gaps：覆盖缺口（高频问题簇还没有 FAQ 草稿）
 * 红线：不臆造政策答复（草稿必须引用真实条目原文）、不直接发布。
 */
@Service
public class FaqBuilderService {

    private final FaqBuilderProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Map<String, Object>> entries = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> drafts = new LinkedHashMap<>();

    public FaqBuilderService(FaqBuilderProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void load() {
        Path file = Path.of(properties.dataDirectory(), "faq.json");
        if (Files.exists(file)) {
            try {
                Map<String, List<Map<String, Object>>> data = objectMapper.readValue(file.toFile(), Map.class);
                for (Map<String, Object> e : data.getOrDefault("entries", List.of())) {
                    entries.put(String.valueOf(e.get("id")), e);
                }
                for (Map<String, Object> d : data.getOrDefault("drafts", List.of())) {
                    drafts.put(String.valueOf(d.get("id")), d);
                }
            } catch (IOException e) {
                throw new IllegalStateException("无法读取 FAQ 数据", e);
            }
        }
        if (entries.isEmpty()) {
            seedDemoEntries();
        }
    }

    private void seedDemoEntries() {
        ingestRecord("客服对话 1", "用户：发票怎么开？\n客服：下单后在我的订单-申请开票里填写抬头，1 个工作日内开好。",
                "客服对话 1：用户：发票怎么开？\n客服：下单后在我的订单-申请开票里填写抬头，1 个工作日内开好。");
        ingestRecord("客服对话 2", "用户：能开发票吗？\n客服：可以，订单完成后申请开票就行。",
                "客服对话 2：用户：能开发票吗？\n客服：可以，订单完成后申请开票就行。");
        ingestRecord("客服对话 3", "用户：退货流程是什么？\n客服：收货 7 天内，在我的订单-申请退货，寄回后 3 天退款。",
                "客服对话 3：用户：退货流程是什么？\n客服：收货 7 天内，在我的订单-申请退货，寄回后 3 天退款。");
        ingestRecord("客服对话 4", "用户：东西不喜欢想退。\n客服：没问题，7 天内无理由退货，先在订单里点申请退货。",
                "客服对话 4：用户：东西不喜欢想退。\n客服：没问题，7 天内无理由退货，先在订单里点申请退货。");
        ingestRecord("反馈 5", "用户反馈：为什么优惠券用不了？提示已过期。",
                "反馈 5：用户反馈：为什么优惠券用不了？提示已过期。");
        ingestRecord("反馈 6", "用户反馈：优惠券怎么用？下单没看到优惠入口。",
                "反馈 6：用户反馈：优惠券怎么用？下单没看到优惠入口。");
    }

    private void ingestRecord(String title, String summary, String raw) {
        String id = "entry-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", id);
        e.put("title", title);
        e.put("summary", summary);
        e.put("raw", raw);
        e.put("questionSide", extractQuestion(raw));
        e.put("answerSide", extractAnswer(raw));
        e.put("status", "new");
        e.put("ingestedAt", Instant.now().toString());
        entries.put(id, e);
    }

    private void seedPersist() {
        persist();
    }

    // ---------- 1. 录入 ----------

    public Map<String, Object> ingest(String title, String content) {
        if (content == null || content.trim().length() < 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "内容太短（至少 10 字符）");
        }
        String id = "entry-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", id);
        e.put("title", title == null || title.isBlank() ? "记录-" + id.substring(6) : title);
        e.put("summary", content.trim().length() > 80 ? content.trim().substring(0, 80) + "…" : content.trim());
        e.put("raw", content.trim());
        e.put("questionSide", extractQuestion(content));
        e.put("answerSide", extractAnswer(content));
        e.put("status", "new");
        e.put("ingestedAt", Instant.now().toString());
        entries.put(id, e);
        persist();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entryId", id);
        result.put("totalEntries", entries.size());
        result.put("summary", e.get("summary"));
        return result;
    }

    private String extractQuestion(String raw) {
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.startsWith("用户：") || t.startsWith("用户:")) return t.substring(3).trim();
            if (t.contains("？") && !t.startsWith("客服")) return t;
        }
        return "";
    }

    private String extractAnswer(String raw) {
        List<String> answers = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.startsWith("客服：") || t.startsWith("客服:")) answers.add(t.substring(3).trim());
        }
        return String.join(" ", answers);
    }

    // ---------- 2. 聚类 ----------

    private static final Map<String, List<String>> CLUSTER_KEYWORDS = new LinkedHashMap<>() {{
        put("发票开票", List.of("发票", "开票", "开"));
        put("退货退款", List.of("退货", "退款", "退"));
        put("优惠券", List.of("优惠券", "优惠", "券"));
        put("配送物流", List.of("配送", "物流", "快递", "发货"));
        put("账号密码", List.of("密码", "登录", "账号", "注册"));
    }};

    public synchronized Map<String, Object> clusterQuestions() {
        Map<String, List<String>> clusters = new LinkedHashMap<>();
        for (Map<String, Object> e : entries.values()) {
            String q = String.valueOf(e.get("questionSide"));
            if (q.isBlank()) q = String.valueOf(e.get("summary"));
            String best = "其他";
            int bestHits = 0;
            for (var ck : CLUSTER_KEYWORDS.entrySet()) {
                int hits = 0;
                for (String kw : ck.getValue()) {
                    if (q.contains(kw) || String.valueOf(e.get("raw")).contains(kw)) hits++;
                }
                if (hits > bestHits) { bestHits = hits; best = ck.getKey(); }
            }
            clusters.computeIfAbsent(best, k -> new ArrayList<>()).add(String.valueOf(e.get("id")));
        }
        List<Map<String, Object>> clusterList = clusters.entrySet().stream()
                .map(kv -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("topic", kv.getKey());
                    c.put("count", kv.getValue().size());
                    c.put("entryIds", kv.getValue());
                    return c;
                })
                .sorted((a, b) -> ((Number) b.get("count")).intValue() - ((Number) a.get("count")).intValue())
                .collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalEntries", entries.size());
        result.put("clusterCount", clusterList.size());
        result.put("clusters", clusterList);
        return result;
    }

    // ---------- 3. 草稿 ----------

    public synchronized Map<String, Object> draftAnswer(String topic, List<String> entryIds) {
        if (topic == null || topic.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "topic 不能为空");
        }
        // 红线：草稿必须基于真实条目，没有条目就不臆造
        if (entryIds == null || entryIds.isEmpty()) {
            // 自动取该 topic 簇的条目
            Map<String, Object> clusters = clusterQuestions();
            for (Map<String, Object> c : (List<Map<String, Object>>) clusters.get("clusters")) {
                if (String.valueOf(c.get("topic")).equals(topic)) {
                    entryIds = (List<String>) c.get("entryIds");
                    break;
                }
            }
            if (entryIds == null || entryIds.isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "没有「" + topic + "」相关条目——红线：不臆造政策答复，请先 ingest 真实客服记录");
            }
        }
        List<Map<String, Object>> sources = new ArrayList<>();
        List<String> answers = new ArrayList<>();
        List<String> questions = new ArrayList<>();
        for (String id : entryIds) {
            Map<String, Object> e = entries.get(id);
            if (e == null) continue;
            sources.add(Map.of("entryId", id, "title", e.get("title"), "raw", e.get("raw")));
            String ans = String.valueOf(e.get("answerSide"));
            if (!ans.isBlank()) answers.add(ans);
            String q = String.valueOf(e.get("questionSide"));
            if (!q.isBlank()) questions.add(q);
        }
        if (answers.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "条目里没有客服答复，无法起草（不臆造政策答复）");
        }
        // 草稿答案 = 引用最多的答复要点（拼接真实答复，不改写政策）
        String representative = answers.get(0);
        if (answers.size() > 1) {
            // 取与其它答复重合关键词最多的
            int bestScore = -1;
            for (String a : answers) {
                int score = 0;
                for (String other : answers) {
                    if (a.equals(other)) continue;
                    for (String w : a.split("[，。；、,.;\\s]+")) {
                        if (w.length() >= 2 && other.contains(w)) score++;
                    }
                }
                if (score > bestScore) { bestScore = score; representative = a; }
            }
        }
        String draftQ = questions.isEmpty() ? topic : questions.get(0);
        String id = "draft-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", id);
        d.put("topic", topic);
        d.put("question", draftQ);
        d.put("answerDraft", representative);
        d.put("basedOnCount", answers.size());
        d.put("sourceEntryIds", entryIds);
        d.put("status", "draft");
        d.put("createdAt", Instant.now().toString());
        drafts.put(id, d);
        persist();
        Map<String, Object> result = new LinkedHashMap<>(d);
        result.put("sources", sources);
        result.put("note", "草稿基于 " + answers.size() + " 条真实客服答复整理，未添加任何未在记录中出现的政策——发布前请人工审核");
        return result;
    }

    // ---------- 4. 去重 ----------

    public synchronized Map<String, Object> dedupe() {
        List<String> removed = new ArrayList<>();
        List<String> keepOrder = new ArrayList<>();
        Map<String, Integer> simCount = new LinkedHashMap<>();
        List<Map<String, Object>> all = new ArrayList<>(drafts.values());
        for (int i = 0; i < all.size(); i++) {
            Map<String, Object> a = all.get(i);
            String idA = String.valueOf(a.get("id"));
            if (removed.contains(idA)) continue;
            for (int j = i + 1; j < all.size(); j++) {
                Map<String, Object> b = all.get(j);
                String idB = String.valueOf(b.get("id"));
                if (removed.contains(idB)) continue;
                if (similar(String.valueOf(a.get("question")), String.valueOf(b.get("question")))) {
                    // 保留 basedOnCount 更大的
                    int ba = ((Number) a.get("basedOnCount")).intValue();
                    int bb = ((Number) b.get("basedOnCount")).intValue();
                    String drop = bb > ba ? idA : idB;
                    removed.add(drop);
                    simCount.put(drop, simCount.getOrDefault(drop, 0) + 1);
                }
            }
        }
        removed.forEach(drafts::remove);
        if (!removed.isEmpty()) persist();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalDrafts", drafts.size());
        result.put("removedCount", removed.size());
        result.put("removedIds", removed);
        result.put("rule", "同题相似草稿合并：保留引用条目数（basedOnCount）更多的版本");
        return result;
    }

    private boolean similar(String q1, String q2) {
        if (q1.equals(q2)) return true;
        Set<String> w1 = new HashSet<>(Arrays.asList(q1.split("[，。；、,.;\\s？?]+")));
        Set<String> w2 = new HashSet<>(Arrays.asList(q2.split("[，。；、,.;\\s？?]+")));
        if (w1.isEmpty() || w2.isEmpty()) return false;
        long inter = w1.stream().filter(w2::contains).count();
        double jaccard = (double) inter / (w1.size() + w2.size() - inter);
        return jaccard >= 0.4;
    }

    // ---------- 5. 覆盖缺口 ----------

    public synchronized Map<String, Object> coverageGaps() {
        Map<String, Object> clusters = clusterQuestions();
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) clusters.get("clusters")) {
            String topic = String.valueOf(c.get("topic"));
            int count = ((Number) c.get("count")).intValue();
            boolean hasDraft = drafts.values().stream().anyMatch(d -> String.valueOf(d.get("topic")).equals(topic));
            if (!hasDraft && count >= 2) {
                Map<String, Object> gap = new LinkedHashMap<>();
                gap.put("topic", topic);
                gap.put("askCount", count);
                gap.put("severity", count >= 4 ? "high" : "medium");
                gap.put("suggestion", "该问题被问了 " + count + " 次但还没有 FAQ 草稿，建议用 fq_draft_answer 生成草稿");
                gaps.add(gap);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gapCount", gaps.size());
        result.put("gaps", gaps);
        result.put("draftedTopics", drafts.values().stream().map(d -> d.get("topic")).distinct().collect(Collectors.toList()));
        return result;
    }

    // ---------- 查询 ----------

    public List<Map<String, Object>> listEntries() {
        return new ArrayList<>(entries.values());
    }

    public List<Map<String, Object>> listDrafts() {
        return new ArrayList<>(drafts.values());
    }

    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalEntries", entries.size());
        result.put("totalDrafts", drafts.size());
        result.put("draftedTopics", drafts.values().stream().map(d -> d.get("topic")).distinct().count());
        return result;
    }

    private void persist() {
        try {
            Path file = Path.of(properties.dataDirectory(), "faq.json");
            Files.createDirectories(file.getParent());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("entries", entries.values());
            data.put("drafts", drafts.values());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
        } catch (IOException e) {
            throw new IllegalStateException("无法保存 FAQ 数据", e);
        }
    }
}
