package org.example.englishquestionbank.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.entity.Word;
import org.example.englishquestionbank.mapper.WordMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 词形归一化方案验证器（临时）。
 *
 * <p><b>为什么要验证</b>：{@code WordMarkService} 的归一化方案必须先证明可行，
 * 否则 Service 建在错误假设上会整体返工。
 *
 * <p><b>第一次探测的发现</b>：仅靠「查词典 + {@code exchange} 的 {@code 0:lemma} 段」
 * 是不够的 —— 实测 11 个变形词中有 4 个（{@code abandoning}、{@code abandons}、
 * {@code gave}、{@code apples}）**压根没有独立词条**。原因是 ECDICT 把变形形式
 * 记录在**原形词条**的 {@code exchange} 字段里，只为其中一部分建了独立条目。
 *
 * <p><b>因此本类验证的最终方案是三级兜底</b>：
 * <ol>
 *   <li>正向查词，命中且 {@code exchange} 含 {@code 0:xxx} → 用 {@code xxx}</li>
 *   <li>正向查词，命中但无 {@code 0:} 段 → 该词条自己就是原形</li>
 *   <li>正向未命中 → <b>反查 {@code exchange}</b>，找出记录了这个形式的原形词条</li>
 *   <li>仍未命中 → 退化为小写原样，{@code wordId = null}</li>
 * </ol>
 *
 * <p>第 3 步是本方案能覆盖绝大多数变形词的关键。结论确认后本类即可删除。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InflectionProbeChecker implements CommandLineRunner {

    private final WordMapper wordMapper;

    /** 探测项：输入的变形形式 + 期望归一到哪个原形。 */
    private record Probe(String surface, String expectedLemma) {
    }

    /** 归一化结果：原形 + 命中的词条 id + 走的哪条路径（用于统计与排查）。 */
    private record Normalized(String form, Long wordId, String path, String evidence) {
    }

    private static final List<Probe> PROBES = List.of(
            // 对照组：本身就是原形，exchange 里不应出现 0: 段
            new Probe("abandon", "abandon"),
            // 第一次探测中「正向命中」的
            new Probe("abandoned", "abandon"),
            new Probe("perceived", "perceive"),
            new Probe("running", "run"),
            new Probe("taken", "take"),
            new Probe("children", "child"),
            new Probe("better", "good"),
            // 第一次探测中「未收录」，需要靠反查补齐的
            new Probe("abandoning", "abandon"),
            new Probe("abandons", "abandon"),
            new Probe("gave", "give"),
            new Probe("apples", "apple")
    );

    @Override
    public void run(String... args) {
        log.info("============== 词形归一化方案验证（三级兜底）==============");
        try {
            int forwardHit = 0;
            int reverseHit = 0;
            int missed = 0;
            int wrong = 0;
            List<String> failures = new ArrayList<>();

            for (Probe probe : PROBES) {
                Normalized result = normalize(probe.surface());
                boolean ok = probe.expectedLemma().equalsIgnoreCase(result.form());

                switch (result.path()) {
                    case "正向" -> forwardHit++;
                    case "反查" -> reverseHit++;
                    default -> missed++;
                }
                if (!ok) {
                    wrong++;
                    failures.add(String.format("%s 期望 %s 实得 %s（%s）",
                            probe.surface(), probe.expectedLemma(), result.form(), result.path()));
                }

                log.info("  {}", String.format("%-12s → %-12s [%-4s] %s   %s",
                        probe.surface(), result.form(), result.path(),
                        ok ? "✅" : "❌", result.evidence()));
            }

            log.info("----------------------------------------------------------");
            log.info("共 {} 项：正向命中 {} / 反查命中 {} / 完全未命中 {} / 归一出错 {}",
                    PROBES.size(), forwardHit, reverseHit, missed, wrong);

            if (wrong == 0) {
                log.info("结论：✅ 三级兜底方案可行，WordMarkService 按此实现");
            } else {
                log.warn("结论：⚠ 仍有 {} 项归一错误，需要调整方案：", wrong);
                for (String f : failures) {
                    log.warn("  - {}", f);
                }
            }
            log.info("==========================================================");
        } catch (Throwable t) {
            log.error("词形验证异常：{} - {}", t.getClass().getName(), t.getMessage());
        }
    }

    /**
     * 三级兜底归一化 —— 这就是 {@code WordMarkService} 将要采用的算法，在此先行验证。
     */
    private Normalized normalize(String surface) {
        String lower = surface.toLowerCase();

        // 第 1、2 级：正向查词
        Word direct = wordMapper.selectByHeadword(lower);
        if (direct != null) {
            String lemma = extractLemma(direct.getExchange());
            if (lemma != null) {
                return new Normalized(lemma, direct.getId(), "正向",
                        "exchange 含 0:" + lemma);
            }
            return new Normalized(direct.getHeadword(), direct.getId(), "正向",
                    "无 0: 段，自身即原形");
        }

        // 第 3 级：反查 exchange
        Word viaExchange = wordMapper.selectByExchangeForm(lower);
        if (viaExchange != null) {
            return new Normalized(viaExchange.getHeadword(), viaExchange.getId(), "反查",
                    "由 " + viaExchange.getHeadword() + " 的 exchange 定位");
        }

        // 第 4 级：退化
        return new Normalized(lower, null, "未命中", "词典无此形式，原样保留");
    }

    /**
     * 从 exchange 中解析 lemma。
     *
     * <p>格式形如 {@code d:perceived/p:perceived/3:perceives/i:perceiving}，
     * 各段以 {@code /} 分隔，{@code 0:} 段即 lemma。本身是原形的词条没有这段，返回 null。
     */
    private static String extractLemma(String exchange) {
        if (exchange == null || exchange.isEmpty()) {
            return null;
        }
        for (String segment : exchange.split("/")) {
            if (segment.startsWith("0:")) {
                String lemma = segment.substring(2).trim();
                return lemma.isEmpty() ? null : lemma;
            }
        }
        return null;
    }
}
