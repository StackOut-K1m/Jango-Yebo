package com.jangoyebo.core.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공고 모집 상태를 매일 다시 맞춘다 (P1-15).
 *
 * <p><b>{@code SUPPORT_PROGRAM.status} 는 쓰는 순간의 스냅샷이다.</b> 수집(17.5)과 구조화
 * 콜백(20.6)이 그때의 날짜로 계산해 넣고, 그 뒤로는 아무도 손대지 않는다. 그래서 달력이
 * 넘어가는 것만으로 낡는다 — 게다가 <b>마감된 공고는 출처 목록에서 빠져 재수집되지도
 * 않으므로</b> 스스로 고쳐질 길이 없다.
 *
 * <p>그 결과가 검색(17.1)에서 바로 보였다. 기본 필터가 {@code status} 만 봤을 때
 * <b>「마감된 공고도 보기」 체크박스가 아무 일도 하지 않는 것처럼 보였다</b> — 체크를 풀어도
 * 이미 지난 공고가 다 나오고 있었기 때문이다(2026-09-18 배포 DB 실측 — {@code 'OPEN'}
 * 147건 중 16건이 마감일을 지나 있었고 {@code 'CLOSED'} 행은 하나도 없었다).
 *
 * <p>검색 쪽은 날짜를 함께 보도록 고쳐 목록이 즉시 맞지만, 그것만으로는 <b>화면의 상태
 * 배지가 「접수 중」으로 남는다.</b> 목록에서 빠진 공고도 상세·추천·알림에서는 여전히
 * {@code status} 로 말하므로 값 자체를 맞춰야 한다.
 *
 * <p>판정은 {@link SupportProgram#refreshStatus()} 에 맡긴다 — 같은 규칙을 SQL 로 한 벌 더
 * 쓰면 둘이 갈라지는 날 화면과 목록이 서로 다른 말을 한다(도메인 규칙 2 — 날짜 계산은
 * 백엔드 한 곳에서).
 */
@Component
public class SupportProgramStatusScheduler {

    private static final Logger log = LoggerFactory.getLogger(SupportProgramStatusScheduler.class);

    private final SupportProgramRepository programs;

    public SupportProgramStatusScheduler(SupportProgramRepository programs) {
        this.programs = programs;
    }

    /**
     * 자정 직후에 돈다. 상태가 바뀌는 경계가 날짜라 하루가 바뀌자마자 맞추는 것이 맞고,
     * 수집 배치보다 앞서도 뒤서도 결과는 같다 — 둘 다 같은 규칙으로 계산한다.
     *
     * <p><b>전량을 읽는다.</b> 공고 한 건이 한 행이라 규모가 수백~수천에 머물고, 「바뀔
     * 만한 행」을 SQL 로 추리려면 판정 규칙을 여기에 한 벌 더 쓰게 된다. 그 대가가 더 크다.
     */
    @Scheduled(cron = "${jango.support.status-refresh-cron:0 5 0 * * *}", zone = "Asia/Seoul")
    @Transactional
    public void refreshStatuses() {
        int changed = 0;
        for (SupportProgram program : programs.findAll()) {
            if (program.refreshStatus()) {
                changed++;
            }
        }
        if (changed > 0) {
            log.info("공고 모집 상태 갱신 — {}건", changed);
        }
    }
}
