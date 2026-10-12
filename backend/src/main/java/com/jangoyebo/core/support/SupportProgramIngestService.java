package com.jangoyebo.core.support;

import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.job.JobType;
import com.jangoyebo.core.support.SupportProgramDtos.BatchRequest;
import com.jangoyebo.core.support.SupportProgramDtos.BatchResponse;
import com.jangoyebo.core.support.SupportProgramDtos.Item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 공고 배치 적재 (명세 20.8).
 *
 * <p>수집기가 매일 전량을 보내고 여기서 {@code (source_name, external_id)} 로 upsert 한다.
 * 전량을 받는 쪽이 놓치는 공고가 없고, 증분을 계산하는 상태를 수집기에 두지 않아도 된다.
 *
 * <p><b>이 클래스가 지키는 것은 두 가지 비용이다.</b>
 *
 * <ol>
 *   <li><b>LLM</b> — 구조화는 처음 보는 것과 실제로 바뀐 것에만 건다. 안 가르면 1,526건을
 *       매일 다시 읽는다</li>
 *   <li><b>출처 서버</b> — {@code needBody} 로 <b>원문이 필요한 것만</b> 알려 준다. 수집기는
 *       그것만 상세 페이지를 받는다. 이게 없으면 매일 1,526번 남의 서버를 두드린다</li>
 * </ol>
 */
@Service
public class SupportProgramIngestService {

    private static final Logger log = LoggerFactory.getLogger(SupportProgramIngestService.class);

    private final SupportProgramRepository programs;
    private final JobService jobService;

    public SupportProgramIngestService(SupportProgramRepository programs, JobService jobService) {
        this.programs = programs;
        this.jobService = jobService;
    }

    @Transactional
    public BatchResponse ingest(BatchRequest request) {
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int queued = 0;
        List<String> needBody = new ArrayList<>();

        for (Item item : request.items()) {
            var existing = programs.findBySourceNameAndExternalId(
                    request.sourceSite(), item.externalId());

            // 이번 요청이 본문을 싣고 왔나 (수집기 2단계). 낡은 원문과 가르는 기준이다.
            boolean freshBody = item.rawText() != null && !item.rawText().isBlank();

            SupportProgram program;
            boolean needsStructuring;
            boolean storedBodyIsStale = false;

            if (existing.isEmpty()) {
                program = programs.save(SupportProgram.collected(
                        request.sourceSite(), item.externalId(), item.title(), item.sourceUrl(),
                        item.organizer(), item.rawText(), item.rawSummary(),
                        item.sourceUpdatedAt(), request.collectedAt()));
                created++;
                needsStructuring = true;
            } else {
                program = existing.get();
                // ⚠ refreshFrom 이 값을 덮기 전에 비교해야 한다.
                storedBodyIsStale = item.sourceUpdatedAt() != null
                        && !item.sourceUpdatedAt().equals(program.getSourceUpdatedAt());

                boolean changed = program.refreshFrom(item.title(), item.sourceUrl(),
                        item.organizer(), item.rawText(), item.rawSummary(),
                        item.sourceUpdatedAt(), request.collectedAt());
                if (changed) {
                    updated++;
                } else {
                    unchanged++;
                }
                // 아직 한 번도 읽히지 않았으면 안 바뀌었어도 걸어야 한다 — 지난번 구조화가
                // 실패했거나, 원문이 이번에 처음 붙은 경우다.
                needsStructuring = changed || !program.isStructured();
            }

            // 출처가 적어 둔 기간이 정본이다. 모델에게 다시 뽑게 하지 않는다.
            program.applyPeriod(item.applyStart(), item.applyEnd());

            if (!needsStructuring) {
                continue;
            }

            // **출처가 고쳤으면 갖고 있던 원문은 낡았다.** 그걸로 구조화하면 옛 조건이
            // 새 공고의 자격으로 저장된다 — 연장·정정된 공고에서 바로 일어나는 일이다.
            // 이번에 새로 받아 온 본문이 아니면 수집기에게 다시 받아 오라고 한다.
            if (!freshBody && (storedBodyIsStale || !program.hasRawText())) {
                // 요약만으로 자격 조건을 만들게 하지도 않는다.
                needBody.add(item.externalId());
                continue;
            }

            enqueueStructuring(program);
            queued++;
        }

        log.info("공고 적재 — 출처={} 받음={} 신규={} 갱신={} 그대로={} 구조화큐={} 본문필요={}",
                request.sourceSite(), request.items().size(), created, updated, unchanged,
                queued, needBody.size());

        return new BatchResponse(request.items().size(), created, updated, unchanged,
                queued, List.copyOf(needBody));
    }

    /**
     * 구조화 작업을 큐에 넣는다 (20.6).
     *
     * <p><b>사업장이 없다.</b> 공고는 사업장 공용 데이터라 소유 사업장이 없고, {@code JOB}
     * 은 V21 에서 그 자리를 비울 수 있게 됐다.
     *
     * <p><b>{@code enqueueIfIdle} 을 쓰지 않는다.</b> 그 중복 방지는 사업장 단위로 도는데
     * {@code business_id = null} 은 SQL 에서 아무것도 매칭하지 않아 효과가 없다. 대신 위에서
     * <b>실제로 바뀐 것만</b> 여기 도달하게 막았다 — 같은 공고가 하루에 두 번 바뀌고 그
     * 사이에 첫 작업이 안 끝나야 중복이 생기는데, 하루 1회 배치에서는 사실상 없다.
     * 잃는 것도 LLM 호출 한 번뿐이다.
     */
    private void enqueueStructuring(SupportProgram program) {
        programs.flush();   // id 가 있어야 params 에 넣는다
        jobService.enqueue(null, null, JobType.PROGRAM_STRUCTURING,
                Map.of("programId", program.getId()));
    }
}
