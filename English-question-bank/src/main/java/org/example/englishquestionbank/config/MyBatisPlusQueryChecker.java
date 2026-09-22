package org.example.englishquestionbank.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.entity.Word;
import org.example.englishquestionbank.mapper.WordMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MyBatis-Plus 查询链路自检。
 *
 * <p><b>为什么需要这个类</b>：在此之前我们只验证了「能连上 MySQL」和「MyBatis-Plus 装配成功」，
 * 但从未验证过「能通过 ORM 真的把数据查出来」。这是整个技术栈里最后一个未知项。
 *
 * <p>它分四步递进验证，覆盖 ORM 的三种典型查询形态：
 * <ol>
 *   <li><b>BaseMapper 白送的 CRUD</b> —— {@code selectCount} 聚合查询，证明继承即可用</li>
 *   <li><b>自定义 SQL 返回集合</b> —— {@code @Select} 注解 + 参数绑定 + 排序 + LIMIT</li>
 *   <li><b>自定义 SQL 返回单个对象</b> —— 划词查释义的核心查询，验证结果集到实体的字段映射</li>
 *   <li><b>自定义 SQL 返回标量</b> —— 验证「空格分隔标签」的按词边界匹配写法正确</li>
 * </ol>
 *
 * <p>只要这个类能启动并打出 4 行结果，就说明「实体映射 + Mapper 注册 + SQL 执行 + 结果映射」
 * 整条链路是通的。
 *
 * <p>属于开发期辅助类，链路确认无误后可以删除。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MyBatisPlusQueryChecker implements CommandLineRunner {

    private final WordMapper wordMapper;

    /** 用于验证精确查词的示例词条。 */
    private static final String SAMPLE_WORD = "abandon";

    /** 统计考研词汇时使用的标签（ky = 考研）。 */
    private static final String EXAM_TAG = "ky";

    @Override
    public void run(String... args) {
        log.info("================ MyBatis-Plus 查询链路自检 ================");
        try {
            checkTotal();
            checkTopByFrequency();
            checkExactLookup();
            checkCountByTag();

            log.info("================ ✅ ORM 查询链路正常 ================");
        } catch (Throwable t) {
            log.error("================ ❌ ORM 查询链路异常 ================");
            log.error("异常类型 : {}", t.getClass().getName());
            log.error("异常信息 : {}", t.getMessage());
            log.error("排查方向 :");
            log.error("  1) word 表是否真的有数据：SELECT COUNT(*) FROM word;");
            log.error("  2) 实体字段与表列是否对得上（尤其 id 与自增策略）");
            log.error("  3) WordMapper 是否被扫描到：看启动日志里 ClassPathMapperScanner 的警告");
            log.error("======================================================");
            // 与其它自检类保持一致：不抛异常，让应用继续启动，便于用 /actuator/health 观察
        }
    }

    /** 第 1 步：BaseMapper 自带方法，零 SQL 即可执行聚合查询。 */
    private void checkTotal() {
        Long total = wordMapper.selectCount(Wrappers.emptyWrapper());
        log.info("[1/4] BaseMapper.selectCount（白送的 CRUD） : {} 条", total);
        if (total == null || total == 0) {
            log.warn("      ⚠ 表里没有数据，后续几步的结果会是空的");
        }
    }

    /** 第 2 步：自定义 SQL 返回集合，验证参数绑定与排序。 */
    private void checkTopByFrequency() {
        List<Word> top = wordMapper.selectTopByFrequency(5);
        log.info("[2/4] 自定义 SQL 查询词频前 {} 条 :", top.size());
        for (int i = 0; i < top.size(); i++) {
            Word w = top.get(i);
            // 注意：SLF4J 的 {} 占位符不支持 String.format 的宽度语法（如 %-12s / {:<12}）。
            // 需要对齐时必须先用 String.format 拼好整行，再作为「一个」参数传给 log。
            String line = String.format("%d. %-12s [%s] %s | %s",
                    i + 1,
                    w.getHeadword(),
                    w.getPhoneticUk() == null ? "-" : w.getPhoneticUk(),
                    w.getPartOfSpeech() == null ? "-" : w.getPartOfSpeech(),
                    abbreviate(w.getTranslation(), 40));
            log.info("        {}", line);
        }
    }

    /** 第 3 步：查询单个实体，这是划词查释义的核心路径。 */
    private void checkExactLookup() {
        // 按项目规范：单表等值查询属于「简单操作」，用构造器在调用方表达，
        // 不在 Mapper 接口里声明方法（见根目录 AGENTS.md 的 SQL 书写规范）。
        Word w = wordMapper.selectOne(
                Wrappers.<Word>lambdaQuery().eq(Word::getHeadword, SAMPLE_WORD));
        if (w == null) {
            log.warn("[3/4] 精确查询 '{}' : 词典中未找到（可能被筛选条件排除）", SAMPLE_WORD);
            return;
        }
        log.info("[3/4] 精确查询 '{}' :", SAMPLE_WORD);
        log.info("        音标 : {}", w.getPhoneticUk());
        log.info("        词性 : {}", w.getPartOfSpeech());
        log.info("        标签 : {}", w.getTag());
        log.info("        词频 : frq={} bnc={}", w.getFrq(), w.getBnc());
        log.info("        释义 : {}", w.getTranslation());
    }

    /** 第 4 步：自定义 SQL 返回标量，验证空格分隔标签的按词边界匹配。 */
    private void checkCountByTag() {
        long count = wordMapper.countByExamTag(EXAM_TAG);
        log.info("[4/4] 带有 '{}' 标签（考研）的词条数 : {}", EXAM_TAG, count);
    }

    /** 截断过长的释义，避免刷屏。 */
    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "-";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
