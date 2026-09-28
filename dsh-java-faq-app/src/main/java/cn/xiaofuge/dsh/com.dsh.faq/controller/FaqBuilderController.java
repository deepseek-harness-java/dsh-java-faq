package cn.xiaofuge.dsh.com.dsh.faq.controller;

import cn.xiaofuge.dsh.com.dsh.faq.config.FaqBuilderProperties;
import cn.xiaofuge.dsh.com.dsh.faq.model.ApiException;
import cn.xiaofuge.dsh.com.dsh.faq.service.FaqBuilderService;
import cn.xiaofuge.dsh.com.dsh.faq.service.HarnessAiStreamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
public class FaqBuilderController {
    private final FaqBuilderService faqService;
    private final HarnessAiStreamService harnessAiStreamService;
    private final FaqBuilderProperties properties;

    public FaqBuilderController(FaqBuilderService faqService,
                                HarnessAiStreamService harnessAiStreamService,
                                FaqBuilderProperties properties) {
        this.faqService = faqService;
        this.harnessAiStreamService = harnessAiStreamService;
        this.properties = properties;
    }

    public record IngestRequest(String title, String content) {
    }

    public record DraftRequest(String topic, List<String> entryIds) {
    }

    @GetMapping("/api/faq/entries")
    public List<Map<String, Object>> entries() {
        return faqService.listEntries();
    }

    @GetMapping("/api/faq/drafts")
    public List<Map<String, Object>> drafts() {
        return faqService.listDrafts();
    }

    @GetMapping("/api/faq/stats")
    public Map<String, Object> stats() {
        return faqService.stats();
    }

    @PostMapping("/api/faq/ingest")
    public Map<String, Object> ingest(@RequestBody IngestRequest request) {
        return faqService.ingest(request.title(), request.content());
    }

    @PostMapping("/api/faq/cluster")
    public Map<String, Object> cluster() {
        return faqService.clusterQuestions();
    }

    @PostMapping("/api/faq/draft")
    public Map<String, Object> draft(@RequestBody DraftRequest request) {
        return faqService.draftAnswer(request.topic(), request.entryIds());
    }

    @PostMapping("/api/faq/dedupe")
    public Map<String, Object> dedupe() {
        return faqService.dedupe();
    }

    @PostMapping("/api/faq/gaps")
    public Map<String, Object> gaps() {
        return faqService.coverageGaps();
    }

    @PostMapping(value = "/api/assistant/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter assistant(@RequestBody Map<String, String> request) {
        String agentId = request.getOrDefault("agentId", "");
        String message = request.getOrDefault("message", "");
        if (agentId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "agentId 不能为空");
        }
        if (message.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        return harnessAiStreamService.stream(agentId, buildPrompt(message));
    }

    private String buildPrompt(String message) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("当前环境：\n");
        prompt.append("- FAQ Builder（FAQ 建造器）：客服记录入库、问题聚类、FAQ 草稿、去重、覆盖缺口\n");
        prompt.append("- 你可以使用以下工具（起草/分析前必须先调用工具查证，禁止臆造数据）：\n");
        prompt.append("  - plugin__dsh-java-faq-plugin__fq_ingest(title, content)：客服记录/反馈入库\n");
        prompt.append("  - plugin__dsh-java-faq-plugin__fq_cluster_questions()：问题聚类\n");
        prompt.append("  - plugin__dsh-java-faq-plugin__fq_draft_answer(topic, entryIds)：FAQ 草稿（必须基于真实条目）\n");
        prompt.append("  - plugin__dsh-java-faq-plugin__fq_dedupe()：草稿去重\n");
        prompt.append("  - plugin__dsh-java-faq-plugin__fq_coverage_gaps()：覆盖缺口（高频未覆盖问题）\n");
        prompt.append("\n用户需求：").append(message);
        prompt.append("\n\n回答要求：\n");
        prompt.append("- 使用中文，Markdown 格式，结论先行。\n");
        prompt.append("- 草稿答案只引用工具返回的真实客服答复原文，禁止添加任何未在记录中出现的政策/承诺。\n");
        prompt.append("- 红线：不直接发布 FAQ——草稿状态必须人工审核后由用户自行发布。\n");
        return prompt.toString();
    }
}
