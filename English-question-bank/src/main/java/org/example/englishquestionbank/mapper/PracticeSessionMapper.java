package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.englishquestionbank.entity.PracticeSession;

/**
 * {@code practice_session} 表的 Mapper。
 *
 * <p>继承 {@link BaseMapper} 即获得全部单表 CRUD。
 * 交卷时需要在一次事务里更新会话的汇总字段，那属于 Service 层职责。
 */
@Mapper
public interface PracticeSessionMapper extends BaseMapper<PracticeSession> {
}
