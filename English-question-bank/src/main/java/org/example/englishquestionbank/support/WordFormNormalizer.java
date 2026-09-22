package org.example.englishquestionbank.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.entity.Word;
import org.example.englishquestionbank.mapper.WordMapper;
import org.springframework.stereotype.Component;

/**
 * 词形归一化器：把一个变形形式还原成词典里的原形。
 *
 * <p><b>为什么独立成组件</b>：这套逻辑同时被 {@code WordMarkService}（写入标记时）
 * 和 {@code InflectionProbeChecker}（验证算法）使用。若各写一份，
 * 迟早会漂移成两套行为 —— 让它们共用同一份实现，验证才有意义。
 *
 * <h2>三级兜底</h2>
 * <ol>
 *   <li>正向查词，命中且 {@code exchange} 含 {@code 0:xxx} → 用 {@code xxx}</li>
 *   <li>正向查词，命中但无 {@code 0:} 段 → 该词条自己就是原形</li>
 *   <li>正向未命中 → <b>反查 {@code exchange}</b>，找出记录了这个形式的原形词条</li>
 *   <li>仍未命中 → 退化为小写原样，{@code wordId = null}</li>
 * </ol>
 *
 * <p>第 3 级是必需的：实测 ECDICT <b>不为每个变形都建独立词条</b>，
 * 例如 {@code abandoning} / {@code abandons} / {@code gave} / {@code apples}
 * 都只能靠反查定位原形。只做正向查词会让生词本出现
 * 「{@code run}/{@code running} 能合并、{@code abandon}/{@code abandoning} 不能」的不一致行为。
 *
 * <p>算法实测覆盖 11/11（详见 {@code docs/service-layer.md} 3.4 节）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WordFormNormalizer {

    private final WordMapper wordMapper;

    /**
     * 归一化结果。
     *
     * @param form     归一化后的原形；词典完全未命中时为清洗后的小写原样
     * @param word     命中的词条实体（{@code null} 表示词典未收录）。
     *                 直接带出实体而不是只带 id，是因为归一化过程本来就已经把整行查出来了，
     *                 调用方顺势可以拿到 {@code id} / {@code translation} / {@code displayForm}，
     *                 不必再查一次词典
     * @param path     走的哪一级（{@code 正向} / {@code 反查} / {@code 未命中}），用于日志与排查
     * @param evidence 判定依据的一句话说明
     */
    public record Normalized(String form, Word word, String path, String evidence) {

        /** 命中的词条 id；未命中时为 null。 */
        public Long wordId() {
            return word == null ? null : word.getId();
        }

        /** 中文释义；未命中时为 null。 */
        public String translation() {
            return word == null ? null : word.getTranslation();
        }
    }

    /**
     * 归一化一个原文形式。
     *
     * @param surfaceForm 原文形式，允许带首尾标点，如 {@code "running,"}
     * @return 归一化结果；若清洗后为空串，返回 {@code form=""} 且 {@code word=null}
     */
    public Normalized normalize(String surfaceForm) {
        String lower = clean(surfaceForm);
        if (lower.isEmpty()) {
            return new Normalized("", null, "无效", "清洗后为空串");
        }

        // 第 1、2 级：正向查词（单表等值查询 → 构造器，项目规范）
        Word direct = wordMapper.selectOne(
                Wrappers.<Word>lambdaQuery().eq(Word::getHeadword, lower));
        if (direct != null) {
            String lemma = extractLemma(direct.getExchange());
            if (lemma != null) {
                return new Normalized(lemma, direct, "正向", "exchange 含 0:" + lemma);
            }
            return new Normalized(direct.getHeadword(), direct, "正向", "无 0: 段，自身即原形");
        }

        // 第 3 级：反查 exchange（XML 查询，见 WordMapper.xml）
        Word viaExchange = wordMapper.selectByExchangeForm(lower);
        if (viaExchange != null) {
            return new Normalized(viaExchange.getHeadword(), viaExchange, "反查",
                    "由 " + viaExchange.getHeadword() + " 的 exchange 定位");
        }

        // 第 4 级：退化
        return new Normalized(lower, null, "未命中", "词典无此形式，原样保留");
    }

    /**
     * 清洗原文形式：去掉首尾的非字母字符，并转小写。
     *
     * <p>前端划词时容易把相邻标点一起送来（如 {@code "running,"}），
     * 这里统一剥掉。中间的非字母字符保留，因为 {@code well-known}、{@code don't}
     * 这类词本身含连字符/撇号。
     *
     * <p>只把「首尾」的非字母剥掉，而不是全文过滤非字母 —— 后者会把
     * {@code well-known} 变成 {@code wellknown}，反而查不到。
     */
    public static String clean(String surfaceForm) {
        if (surfaceForm == null) {
            return "";
        }
        return surfaceForm.replaceAll("^[^A-Za-z]+|[^A-Za-z]+$", "").toLowerCase();
    }

    /**
     * 从 {@code exchange} 中解析 lemma。
     *
     * <p>格式形如 {@code d:perceived/p:perceived/3:perceives/i:perceiving}，
     * 各段以 {@code /} 分隔，{@code 0:} 段即 lemma。本身是原形的词条没有这一段，返回 null。
     */
    public static String extractLemma(String exchange) {
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
