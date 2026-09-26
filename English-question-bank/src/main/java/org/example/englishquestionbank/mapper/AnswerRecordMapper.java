package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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
}
