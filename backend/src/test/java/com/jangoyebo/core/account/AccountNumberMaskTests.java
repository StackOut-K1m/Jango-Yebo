package com.jangoyebo.core.account;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계좌번호 마스킹과 입력 형식.
 *
 * <p>마스킹은 <b>구분자를 보존</b>하므로 결과 길이가 계좌번호 자릿수가 아니라 사용자 입력
 * 길이를 따라간다(구현현황 3.7). 그 사실이 {@code account_number_masked} 의 크기를 정하며,
 * V1 은 그것을 숫자 기준으로 잡아 여유가 0 이었다 — V11 이 넓혔다.
 *
 * <p>{@code mask} 와 정규화가 <b>같은 숫자 기준</b>을 쓴다는 것도 여기서 지킨다.
 * {@code Character.isDigit} 는 전각 {@code ０-９} 도 숫자로 보는데 정규화는 {@code [^0-9]} 로
 * 지운다. 갈리면 화면에 보이는 번호와 실제 저장·조회되는 번호가 달라진다.
 *
 * <p>Spring 도 Docker 도 쓰지 않는다 — 순수 함수와 빈 검증뿐이다.
 */
class AccountNumberMaskTests {

    // --- 마스킹 형식 --------------------------------------------------------

    /** 명세 7.2 예시 그대로다. */
    @Test
    void keepsTheSeparatorsTheUserTyped() {
        assertThat(AccountService.mask("110-123-456789")).isEqualTo("110-***-***789");
    }

    @Test
    void masksTheMiddleWhenThereAreNoSeparators() {
        assertThat(AccountService.mask("0014123456789012")).isEqualTo("001**********012");
    }

    /**
     * <b>이 테스트가 컬럼 크기의 근거다.</b> 16자리 계좌번호라도 사용자가 구분자를 넣으면
     * 저장할 값은 그만큼 길어진다 — 컬럼은 자릿수가 아니라 입력 길이를 견뎌야 한다.
     */
    @Test
    void growsWithTheInputNotWithTheDigitCount() {
        String withSeparators = "0014-1234-5678-9012";

        assertThat(AccountService.mask("0014123456789012")).hasSize(16);
        assertThat(AccountService.mask(withSeparators)).hasSize(withSeparators.length());
        // 입력 상한(LinkRequest @Size) 을 꽉 채워도 마스킹 결과가 컬럼(64)을 넘지 않는다.
        assertThat(AccountService.mask("1".repeat(32))).hasSizeLessThanOrEqualTo(64);
    }

    /**
     * 전각 숫자는 숫자로 세지 않는다 — 정규화({@code [^0-9]})가 지우는 문자다.
     * 세어 버리면 자릿수가 부풀어 마스킹 위치가 밀리고, 화면의 번호가 저장된 번호와 어긋난다.
     */
    @Test
    void doesNotCountFullWidthDigitsAsDigits() {
        assertThat(AccountService.mask("００１４-１２３４")).isEqualTo("００１４-１２３４");
    }

    // --- 입력 형식 ----------------------------------------------------------

    /**
     * 전각 숫자를 그대로 받으면 정규화가 전부 지워 <b>빈 문자열</b>이 해시·암호화되고 1원도 그
     * 번호로 나간다. 금융망이 거절해 행은 되감기지만, 사용자에게는 화면에 멀쩡히 보이는 번호를
     * 두고 {@code ACCOUNT_NO_INVALID} 만 뜨는 막다른 길이 된다. 컨트롤러에서 끊는다.
     */
    @Test
    void rejectsFullWidthDigits() {
        assertThat(violationsOn("００１４-１２３４-５６７８-９０１２")).isNotEmpty();
    }

    @Test
    void acceptsDigitsWithOrWithoutSeparators() {
        assertThat(violationsOn("0014123456789012")).isEmpty();
        assertThat(violationsOn("0014-1234-5678-9012")).isEmpty();
        assertThat(violationsOn("110 123 456789")).isEmpty();
    }

    /** 구분자만 있거나 양끝에 붙은 값은 계좌번호가 아니다. */
    @Test
    void rejectsStraySeparators() {
        assertThat(violationsOn("-0014123456789012")).isNotEmpty();
        assertThat(violationsOn("0014123456789012-")).isNotEmpty();
        assertThat(violationsOn("----")).isNotEmpty();
    }

    /** 마스킹이 길이를 보존하므로 입력 상한이 곧 저장될 값의 상한이다. */
    @Test
    void rejectsInputLongerThanTheColumnCanHold() {
        assertThat(violationsOn("1".repeat(33))).isNotEmpty();
    }

    private static Set<ConstraintViolation<AccountController.LinkRequest>> violationsOn(String accountNo) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(
                    new AccountController.LinkRequest("088", accountNo, "주거래", false));
        }
    }
}
