package com.jangoyebo.core.account;

import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.core.job.JobStatus;
import com.jangoyebo.core.job.JobType;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 계좌 연결과 1원 인증 (API 7.2).
 *
 * <p>{@code /accounts/{accountId}} 는 <b>직접 경로</b>라 {@code @CurrentBusiness} 자동 검증이
 * 걸리지 않는다. 소유권 확인은 {@link AccountService} 안에서 명시적으로 한다
 * ({@code BusinessGuard} 주석의 "리뷰에서 확인한다"에 해당하는 지점).
 */
@RestController
@RequestMapping("/api/v1")
public class AccountController {

    private final AccountService accountService;
    private final AccountPurgeService accountPurgeService;

    public AccountController(AccountService accountService,
                             AccountPurgeService accountPurgeService) {
        this.accountService = accountService;
        this.accountPurgeService = accountPurgeService;
    }

    @GetMapping("/businesses/{businessId}/accounts")
    public ApiResponse<Map<String, List<AccountResponse>>> list(@CurrentBusiness Long businessId) {
        List<AccountResponse> items = accountService.list(businessId).stream()
                .map(AccountResponse::from).toList();
        return ApiResponse.ok(Map.of("items", items));
    }

    /** {@code 201} + {@code PENDING_VERIFICATION}. 응답을 받은 시점에 1원은 이미 나갔다. */
    @PostMapping("/businesses/{businessId}/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AccountResponse> link(@CurrentBusiness Long businessId,
                                             @Valid @RequestBody LinkRequest request) {
        Account account = accountService.link(businessId, request.bankCode(), request.accountNo(),
                request.alias(), Boolean.TRUE.equals(request.isPrimary()));
        return ApiResponse.ok(AccountResponse.from(account));
    }

    /** 통장 적요에서 읽은 4자리를 검증한다. 성공하면 {@code CONNECTED} 가 된다. */
    @PostMapping("/accounts/{accountId}/verify")
    public ApiResponse<AccountResponse> verify(@PathVariable Long accountId,
                                               @Valid @RequestBody VerifyRequest request) {
        return ApiResponse.ok(AccountResponse.from(accountService.verify(accountId, request.authCode())));
    }

    /** 1원 재발송. {@code status} 가 {@code PENDING_VERIFICATION} 으로 돌아간다. */
    @PostMapping("/accounts/{accountId}/verification")
    public ApiResponse<AccountResponse> resendVerification(@PathVariable Long accountId) {
        return ApiResponse.ok(AccountResponse.from(accountService.resendVerification(accountId)));
    }

    /**
     * 금융망 거래 동기화 (API 7.3). {@code 202} + Job — 완료는 {@code GET /jobs/{jobId}} 로 본다.
     *
     * <p>인증 전 계좌는 {@code 409 ACCOUNT_NOT_VERIFIED} 다. 인증 직후에는 이 API 를 부를 필요가
     * 없다 — {@code /verify} 가 같은 작업을 자동으로 접수한다(명세 7.2).
     */
    @PostMapping("/accounts/{accountId}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<SyncAcceptedResponse> sync(@PathVariable Long accountId) {
        var accepted = accountService.requestSync(accountId);
        return ApiResponse.ok(new SyncAcceptedResponse(
                accepted.job().getId(), accepted.job().getJobType(),
                accepted.job().getStatus(), accepted.lastSyncedAt()));
    }

    /**
     * 예정 거래 자동 반영 on/off (API 9.6).
     *
     * <p><b>시연용이다.</b> 켜면 서버가 예정일마다 이 계좌에서 실제로 출금·입금한다 —
     * 대상은 우리가 만든 싸피은행 샌드박스 계좌이고, 실사용자 계좌에 켜는 것은
     * 자동 실행 금지 원칙(공통규약 23장)에 어긋난다. 그래서 화면에 두지 않고
     * 시드 도구가 부른다.
     *
     * <p>인증 전 계좌는 {@code 409 ACCOUNT_NOT_VERIFIED} 다.
     */
    @PatchMapping("/accounts/{accountId}/planned-execution")
    public ApiResponse<AccountResponse> setPlannedExecution(
            @PathVariable Long accountId,
            @Valid @RequestBody PlannedExecutionRequest request) {
        return ApiResponse.ok(AccountResponse.from(
                accountService.setAutoExecution(accountId, request.enabled())));
    }

    /** 연동 해제. 이미 수집된 거래는 보존한다. */
    @DeleteMapping("/accounts/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable Long accountId) {
        accountService.disconnect(accountId);
    }

    /**
     * 계좌 삭제 (API 7.2). 계좌와 그 계좌의 거래를 <b>실제로 지운다.</b>
     *
     * <p><b>위의 {@code DELETE} 와 다른 문이다.</b> 그쪽은 해제라 되돌릴 수 있고 거래를
     * 보존한다. 같은 {@code DELETE} 에 플래그로 얹지 않은 이유는 <b>앱이 웹보다 늦게
     * 배포되기 때문이다</b> — 의미를 바꾸면 구버전 앱이 「해제」하려고 부른 호출이 서버에서
     * 하드 삭제가 된다. 되돌릴 수 없는 쪽으로 어긋나는 것이 최악이다.
     *
     * <p>{@code 200} 이다. {@code 204} 로 두면 무엇이 얼마나 지워졌는지 돌려줄 자리가 없는데,
     * 되돌릴 수 없는 일에는 화면이 되짚어 줄 숫자가 있어야 한다.
     */
    @PostMapping("/accounts/{accountId}/purge")
    public ApiResponse<AccountPurgeService.PurgeResult> purge(
            @PathVariable Long accountId,
            @Valid @RequestBody PurgeRequest request) {
        return ApiResponse.ok(accountPurgeService.purge(accountId, request.confirm(),
                Boolean.TRUE.equals(request.purgeOrphanEvidence())));
    }

    /**
     * {@code accountNo} 는 <b>숫자와 구분자만</b> 받는다. 하이픈·공백은 있어도 없어도 되고
     * 서버가 숫자만 남겨 정규화한다 — 구분자는 마스킹 표시에만 쓰인다.
     *
     * <p>형식을 여기서 막는 이유는 {@code Character.isDigit} 가 전각 숫자({@code ０-９})까지
     * 숫자로 보는데 정규화는 {@code [^0-9]} 로 지우기 때문이다. 걸러내지 않으면 사용자가 친
     * 번호와 <b>다른 번호</b>가 해시·암호화되고 1원도 그 번호로 나간다 — 전부 전각이면 빈
     * 문자열이다. 대개 금융망이 {@code A1003} 으로 거절해 {@code 400 ACCOUNT_NO_INVALID} 로
     * 끝나지만(트랜잭션이 되감겨 행은 남지 않는다), <b>사용자에게는 화면에 멀쩡히 보이는 번호를
     * 두고 "계좌번호를 다시 확인해 주세요"만 뜨는 막다른 길</b>이다. 여기서 끊으면 무엇이 문제인지
     * 필드에 적어 줄 수 있다({@link AccountService#mask}).
     */
    public record LinkRequest(
            @NotBlank @Size(max = 10) String bankCode,
            @NotBlank @Size(max = 32)
            @Pattern(regexp = "[0-9]+([ -][0-9]+)*", message = "계좌번호는 숫자와 하이픈만 넣을 수 있습니다.")
            String accountNo,
            @NotBlank @Size(max = 100) String alias,
            Boolean isPrimary) {
    }

    /**
     * 명세 7.3 의 {@code data}. {@code JobResponse} 를 쓰지 않는 이유는 {@code lastSyncedAt}
     * 하나 때문이다 — 그 필드가 Job 이 아니라 계좌의 것이라 {@code JobResponse} 에 넣을 자리가 없다.
     */
    public record SyncAcceptedResponse(Long jobId, JobType jobType, JobStatus status, Instant lastSyncedAt) {
    }

    /** {@code null} 을 받지 않는다 — 「안 보냈다」와 「끄겠다」가 같은 뜻이 되면 안 된다. */
    public record PlannedExecutionRequest(@NotNull Boolean enabled) {
    }

    /**
     * 계좌 삭제 확인.
     *
     * <p>{@code confirm} 은 <b>계좌 별칭</b>이다({@code alias}). 계좌번호를 묻지 않는 이유는
     * 화면에 마스킹밖에 없고 그 마스킹이 앞뒤 3자리씩만 남겨 「뒤 몇 자리」가 모호하기
     * 때문이다 — 사정은 {@code AccountPurgeService#assertConfirmed}. 비밀이 아니라 사용자가
     * 목록에서 보고 옮겨 적는 값이며, 돌이킬 수 없는 일이 버튼 하나로 일어나지 않게 하는 것이
     * 전부다.
     *
     * <p>{@code purgeOrphanEvidence} 는 {@code null} 이면 꺼진 것으로 본다 — 증빙을 지우는
     * 쪽이 기본이 되어서는 안 되므로 「안 보냈다」가 「지운다」가 될 길을 두지 않는다.
     */
    public record PurgeRequest(
            @NotBlank @Size(max = 100) String confirm,
            Boolean purgeOrphanEvidence) {
    }

    /** 금융망이 만드는 코드는 항상 숫자 4자리다(기술스택 §4.1). 형식이 틀리면 부르지 않는다. */
    public record VerifyRequest(@NotBlank @Pattern(regexp = "\\d{4}") String authCode) {
    }

    public record AccountResponse(
            Long accountId, String bankCode, String accountNoMasked, String alias,
            boolean isPrimary, String status, BigDecimal balance,
            Instant balanceUpdatedAt, Instant lastSyncedAt, String syncStatus,
            boolean autoExecutePlanned) {

        static AccountResponse from(Account a) {
            return new AccountResponse(a.getId(), a.getBankCode(), a.getAccountNumberMasked(),
                    a.getName(), a.isPrimaryAccount(), a.getStatus(), a.getCurrentBalance(),
                    a.getBalanceAsOf(), a.getLastSyncedAt(), a.getSyncStatus(),
                    a.isAutoExecutePlanned());
        }
    }
}
