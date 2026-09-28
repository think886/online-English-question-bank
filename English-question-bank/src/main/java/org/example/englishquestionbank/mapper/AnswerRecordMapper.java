package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.englishquestionbank.dto.query.WrongQuestionItem;
import org.example.englishquestionbank.entity.AnswerRecord;

import java.util.List;

/**
 * {@code answer_record} 表的 Mapper。
 *
 * <p>继承 {@link BaseMapper} 获得全部单表 CRUD；复杂 SQL 写在
 * {@code src/main/resources/mapper/AnswerRecordMapper.xml}。
 */
@Mapper
public interface AnswerRecordMapper extends BaseMapper<AnswerRecord> {

    /**
     * 写入或覆盖一条作答记录。
     *
     * <p>唯一键 {@code (session_id, question_id)} 决定语义是<b>覆盖</b>：
     * 同一题重复提交时更新上一次的答案与判分结果，而不是报错或留两行。
     *
     * @param record 作答记录；{@code id} 无需设置，{@code answeredAt} 由 SQL 用 {@code NOW()} 填
     * @return 受影响行数（{@code ON DUPLICATE KEY UPDATE} 的返回值不可靠，仅用于日志）
     */
    int upsertAnswer(@Param("r") AnswerRecord record);

    /**
     * 错题本：取某用户答错过的题，按作答时间倒序。
     *
     * <p>它兑现的是 schema 里的一句设计声明：<b>错题本不需要额外的表</b>，
     * {@code answer_record WHERE is_correct = 0} 就是错题本。
     *
     * @param userId 用户 id
     * @param limit  取多少条
     * @return 答错的作答记录，按作答时间倒序
     */
    List<AnswerRecord> selectWrongByUser(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * 错题本<b>分页</b>查询（带题干、正确答案、解析、来源文章）。
     *
     * <p><b>关键约束</b>：只取<strong>已交卷会话</strong>（{@code practice_session.status = 'FINISHED'}）
     * 里的错题。练习进行中的错题不进错题本 ——
     * 否则这个接口就成了「绕开结果页提前看答案」的入口，违反红线 2。
     * 该过滤条件写在 XML 的 SQL 里。
     *
     * <p>另外只取 {@code is_correct = 0}：翻译题在评分完成前 {@code is_correct} 为 NULL，
     * 那种「还没判分」的记录既不算对也不算错，不应进错题本
     * （{@code IS NULL} 与 {@code = 0} 是两回事，写成 {@code is_correct != 1} 会把它们误收进来）。
     *
     * @param page   分页参数，由 MyBatis-Plus 插件读取并回填 total
     * @param userId 用户 id
     * @return 分页结果；{@code resultType} 为 {@code dto.query.WrongQuestionItem}，
     *         XML 里必须写全限定名
     */
    IPage<WrongQuestionItem> selectWrongPage(IPage<WrongQuestionItem> page, @Param("userId") Long userId);
}
