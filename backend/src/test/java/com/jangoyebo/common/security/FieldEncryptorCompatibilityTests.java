package com.jangoyebo.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FieldEncryptor} 가 {@code Encryptors.delux} 와 <b>암호문 형식이 동일한지</b> 증명한다.
 *
 * <p>이 테스트가 이 리팩터링의 근거 전부다. {@code Encryptors} 가 deprecated 돼 직접 조립으로
 * 옮겼는데, 형식이 조금이라도 다르면 <b>이미 저장된 암호문을 읽을 수 없다.</b> 그래서 손으로 쓴
 * 구현을 신뢰하는 대신 deprecated 구현을 정답지로 두고 <b>양방향 교차 복호화</b>를 확인한다.
 *
 * <p>여기가 빨간색이면 리팩터링을 되돌려야 한다 — 상수를 조정해 통과시키려 하기 전에
 * 어느 파라미터가 다른지부터 확인한다.
 *
 * <p>Spring 컨텍스트도 Docker 도 쓰지 않는다.
 */
class FieldEncryptorCompatibilityTests {

    /** 인프라가 생성한 형식과 같은 hex 64자. 패스프레이즈로 쓰이므로 형식 자체는 무관하다. */
    private static final String KEY = "f9ccf1ec696d4715b35d7ecae7194d36f9ccf1ec696d4715b35d7ecae7194d36";

    private static final String PLAIN = "124-81-00998";

    private final FieldEncryptor ours = new FieldEncryptor(properties(KEY));

    @SuppressWarnings("deprecation") // 정답지로만 쓴다. 운영 코드는 더 이상 이 API 를 쓰지 않는다.
    private final TextEncryptor delux = Encryptors.delux(KEY, deriveSalt(KEY));

    /** delux 로 저장된 기존 암호문을 새 구현이 읽을 수 있어야 한다. 이게 마이그레이션 위험의 핵심이다. */
    @Test
    void readsCiphertextWrittenByDelux() {
        String cipherText = delux.encrypt(PLAIN);

        assertThat(ours.decrypt(cipherText)).isEqualTo(PLAIN);
    }

    /** 반대 방향도 확인한다 — 형식이 같다는 것을 양쪽에서 본다. */
    @Test
    void deluxReadsCiphertextWrittenByUs() {
        String cipherText = ours.encrypt(PLAIN);

        assertThat(delux.decrypt(cipherText)).isEqualTo(PLAIN);
    }

    @Test
    void roundTripsThroughOurImplementation() {
        assertThat(ours.decrypt(ours.encrypt(PLAIN))).isEqualTo(PLAIN);
    }

    /** 한글·긴 문자열도 UTF-8 로 왕복해야 한다. 계좌 별칭·사업장명이 여기 걸린다. */
    @Test
    void handlesMultibyteAndLongText() {
        String text = "서울특별시 강남구 테헤란로 123 4층 잔고예보 주식회사".repeat(5);

        assertThat(ours.decrypt(ours.encrypt(text))).isEqualTo(text);
        assertThat(delux.decrypt(ours.encrypt(text))).isEqualTo(text);
    }

    /** AES + 임의 IV 라 같은 평문도 매번 다른 암호문이 된다 — 암호문으로 검색할 수 없는 이유다. */
    @Test
    void producesDifferentCiphertextForSamePlaintext() {
        assertThat(ours.encrypt(PLAIN)).isNotEqualTo(ours.encrypt(PLAIN));
    }

    /** IV 16바이트 + GCM 태그 16바이트가 앞뒤로 붙는다. hex 라 바이트당 2자다. */
    @Test
    void prependsIvAndAppendsGcmTag() {
        String cipherText = ours.encrypt(PLAIN);
        int bytes = cipherText.length() / 2;

        assertThat(bytes)
                .as("IV 16 + 평문 %d + GCM 태그 16", PLAIN.length())
                .isEqualTo(16 + PLAIN.getBytes(StandardCharsets.UTF_8).length + 16);
    }

    @Test
    void passesNullThrough() {
        assertThat(ours.encrypt(null)).isNull();
        assertThat(ours.decrypt(null)).isNull();
    }

    /** 키가 다르면 복호화할 수 없다. "키를 바꾸면 안 된다"는 제약의 실증이다. */
    @Test
    void cannotDecryptWithDifferentKey() {
        String cipherText = ours.encrypt(PLAIN);
        FieldEncryptor other = new FieldEncryptor(properties(KEY.replaceFirst("f", "a")));

        assertThatThrownBy(() -> other.decrypt(cipherText))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("복호화");
    }

    /** 암호문이 손상되면 GCM 태그 검증이 실패한다. 조용히 쓰레기를 반환하지 않는다. */
    @Test
    void rejectsTamperedCiphertext() {
        String cipherText = ours.encrypt(PLAIN);
        // 마지막 hex 문자를 바꿔 태그를 깨뜨린다.
        char last = cipherText.charAt(cipherText.length() - 1);
        String tampered = cipherText.substring(0, cipherText.length() - 1) + (last == '0' ? '1' : '0');

        assertThatThrownBy(() -> ours.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- helpers -------------------------------------------------------------

    private static SecurityProperties properties(String encryptionKey) {
        return new SecurityProperties(
                new SecurityProperties.Jwt("x".repeat(64), Duration.ofMinutes(30), Duration.ofDays(14)),
                encryptionKey);
    }

    /** FieldEncryptor 의 private deriveSalt 와 같은 계산. 정답지를 같은 조건으로 만들기 위한 것이다. */
    private static String deriveSalt(String encryptionKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
