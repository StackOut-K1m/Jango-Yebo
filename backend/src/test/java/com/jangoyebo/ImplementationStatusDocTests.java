package com.jangoyebo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구현현황 문서의 자기 정합 (docs/api/99_구현현황.md).
 *
 * <p><b>왜 문서를 테스트하는가.</b> 이 문서의 「호출 가능 N개」는 <b>프론트가 판단 근거로
 * 쓰는 숫자</b>다(CLAUDE.md — 「프론트는 여기부터 본다」). 그런데 두 사람이 각자 엔드포인트를
 * 하나씩 열고 각자 「71 → 72」로 적으면, git 은 <b>양쪽 글자가 같아서 충돌로 보지 않는다.</b>
 * 합쳐진 문서는 표에 73줄이 있는데 요약은 72라고 말한다 — 한 건이 없는 것처럼 읽힌다.
 *
 * <p>2026-09-16·17 두 번의 병합에서 실제로 같은 일이 났다. 사람이 세는 것으로는 막지 못한다.
 *
 * <p>절 번호도 같은 종류다. 병합으로 들어온 절과 내가 쓴 절이 같은 번호를 갖게 되어도
 * 아무 데서도 안 터진다 — 문서 안의 「§3.39 참고」가 두 곳 중 어디를 가리키는지 알 수 없게
 * 될 뿐이다. 이것도 실제로 났다.
 *
 * <p><b>Docker가 필요 없다.</b> 파일만 읽으므로 CI 에서 매 커밋 돈다 — 그래야 막는 값이 있다.
 */
class ImplementationStatusDocTests {

    /** 테스트 작업 디렉터리는 {@code backend/} 다. 문서는 저장소 루트 아래에 있다. */
    private static final Path DOC = Path.of("..", "docs", "api", "99_구현현황.md");

    private static final Pattern SUMMARY = Pattern.compile("\\|\\s*호출 가능\\s*\\|\\s*\\*\\*(\\d+)\\*\\*\\s*\\|");
    private static final Pattern ENDPOINT_ROW =
            Pattern.compile("^\\|\\s*(GET|POST|PATCH|PUT|DELETE)\\s*\\|", Pattern.MULTILINE);
    private static final Pattern SECTION = Pattern.compile("^### 3\\.(\\d+)\\s", Pattern.MULTILINE);

    private String document() {
        try {
            // 없으면 건너뛰지 않는다. 건너뛰는 가드는 가드가 아니다 —
            // 경로가 바뀌면 이 검사가 조용히 사라진다.
            assertThat(Files.exists(DOC))
                    .as("구현현황 문서를 찾지 못했다: %s (작업 디렉터리 %s)",
                            DOC, Path.of("").toAbsolutePath())
                    .isTrue();
            return Files.readString(DOC, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 요약의 「호출 가능 N」이 2장 표의 줄 수와 같아야 한다.
     *
     * <p>어긋나는 전형적인 경로는 병합이다. 두 사람이 같은 베이스에서 각자 한 줄씩 열고
     * 각자 같은 숫자를 적으면 git 이 충돌을 내지 않는다.
     */
    @Test
    void summaryCountMatchesTheEndpointTable() {
        String document = document();

        Matcher summary = SUMMARY.matcher(document);
        assertThat(summary.find()).as("요약표에 「호출 가능」 줄이 없다").isTrue();
        int declared = Integer.parseInt(summary.group(1));

        String table = section(document, "## 2. 호출 가능한 엔드포인트");
        int actual = (int) ENDPOINT_ROW.matcher(table).results().count();

        assertThat(declared)
                .as("2장 표에는 %d줄이 있는데 요약은 %d개라고 말한다. "
                        + "표를 세어 요약을 고칠 것 — 병합에서 양쪽이 각자 세면 이렇게 어긋나고, "
                        + "프론트는 이 숫자를 보고 「그 API 는 아직 없구나」로 읽는다.", actual, declared)
                .isEqualTo(actual);
    }

    /**
     * §3 절 번호가 겹치지 않고 오름차순이어야 한다.
     *
     * <p>겹치면 문서 안의 「§3.39 참고」가 두 곳 중 어디를 가리키는지 알 수 없다.
     * 순서가 어긋나면 읽는 사람이 최신 절을 못 찾는다.
     */
    @Test
    void sectionNumbersAreUniqueAndAscending() {
        List<Integer> numbers = new ArrayList<>();
        Matcher matcher = SECTION.matcher(document());
        while (matcher.find()) {
            numbers.add(Integer.parseInt(matcher.group(1)));
        }

        assertThat(numbers).as("§3 아래에 절이 하나도 없다 — 문서 구조가 바뀌었는지 확인할 것")
                .isNotEmpty();
        assertThat(numbers)
                .as("절 번호가 겹치거나 순서가 어긋났다: %s. 새 절은 마지막 번호 + 1 로 맨 뒤에 붙인다",
                        numbers)
                .isSorted()
                .doesNotHaveDuplicates();
    }

    /** {@code ## } 제목 하나의 본문. 다음 {@code ## } 앞까지다. */
    private static String section(String document, String heading) {
        int start = document.indexOf(heading);
        assertThat(start).as("문서에서 「%s」를 찾지 못했다", heading).isNotNegative();
        int end = document.indexOf("\n## ", start + heading.length());
        return end < 0 ? document.substring(start) : document.substring(start, end);
    }
}
