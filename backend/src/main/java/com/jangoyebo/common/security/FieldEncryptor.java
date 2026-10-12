package com.jangoyebo.common.security;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * 컬럼 단위 암호화.
 *
 * <p>대상: 사업자등록번호, 금융망 {@code userKey}, 계좌번호 원문
 * (ERD 11장 — "암호화 저장하고 응답에는 마스킹하거나 노출하지 않는다").
 *
 * <p>AES-256-GCM + 임의 IV 라 <b>같은 평문도 매번 다른 암호문</b>이 된다. 그래서 암호문으로는
 * 검색·비교를 할 수 없다 — 계좌 중복 판정에 별도 {@code account_number_hash} 컬럼을 둔 이유다.
 *
 * <h2>왜 직접 조립하는가</h2>
 *
 * <p>원래 {@code Encryptors.delux} 를 썼는데 Spring Security 7.1.1 에서 {@code Encryptors} 의
 * 팩토리 4종과 구현 클래스 {@code AesBytesEncryptor} 가 <b>전부</b> deprecated 됐다.
 * {@code HexEncodingTextEncryptor} 는 package-private 이라 라이브러리 안에 대체 경로가 없다.
 *
 * <p><b>암호문 형식은 {@code Encryptors.delux} 와 완전히 동일하다.</b> 아래 상수는 바이트코드
 * 실측값이며, {@code FieldEncryptorCompatibilityTests} 가 양방향 교차 복호화로 이를 증명한다.
 * 형식이 같으므로 <b>기존 암호문의 마이그레이션이 필요 없고, 앞으로도 필요 없다.</b>
 *
 * <p>⚠ <b>이 상수들과 {@code APP_ENCRYPTION_KEY} 는 바꿀 수 없다.</b> 하나라도 바뀌면 기존
 * 암호문을 복호화할 수 없다. 암호문이 쌓인 뒤에 방식을 바꾸려면 복호화 → 재암호화
 * 마이그레이션이 필요하고, 그건 키를 알고 있어야만 가능하다(API 공통규약 23장).
 */
@Component
public class FieldEncryptor {

    // --- Encryptors.delux 실측값. 하나라도 바꾸면 기존 암호문을 읽을 수 없다. ---
    private static final String KEY_ALGORITHM = "PBKDF2WithHmacSHA1";
    private static final int ITERATIONS = 1024;
    private static final int KEY_BITS = 256;
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    /** IV 는 암호문 앞에 그대로 붙는다. 비밀이 아니지만 매 암호화마다 새로 뽑아야 한다. */
    private static final int IV_BYTES = 16;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public FieldEncryptor(SecurityProperties properties) {
        this.key = deriveKey(properties.encryptionKey());
    }

    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            byte[] body = cipher(Cipher.ENCRYPT_MODE, iv)
                    .doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(body, 0, combined, iv.length, body.length);
            return HexFormat.of().formatHex(combined);
        } catch (GeneralSecurityException e) {
            // 평문을 예외 메시지에 넣지 않는다.
            throw new IllegalStateException("암호화에 실패했습니다.", e);
        }
    }

    public String decrypt(String cipherText) {
        if (cipherText == null) {
            return null;
        }
        try {
            byte[] combined = HexFormat.of().parseHex(cipherText);
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_BYTES);
            byte[] body = Arrays.copyOfRange(combined, IV_BYTES, combined.length);
            return new String(cipher(Cipher.DECRYPT_MODE, iv).doFinal(body), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // 키가 바뀌었거나 암호문이 손상된 경우다. 암호문을 로그에 남기지 않는다.
            throw new IllegalStateException("복호화에 실패했습니다.", e);
        }
    }

    private Cipher cipher(int mode, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher;
    }

    /**
     * 패스프레이즈 + salt 에서 AES 키를 유도한다.
     *
     * <p>{@code APP_ENCRYPTION_KEY} 는 <b>AES 키가 아니라 패스프레이즈</b>다. 그래서 hex 64자든
     * 임의 문자열이든 동작한다 — 대신 <b>한 글자도 바꿀 수 없다.</b>
     */
    private static SecretKey deriveKey(String encryptionKey) {
        try {
            byte[] salt = HexFormat.of().parseHex(deriveSalt(encryptionKey));
            var spec = new PBEKeySpec(encryptionKey.toCharArray(), salt, ITERATIONS, KEY_BITS);
            byte[] derived = SecretKeyFactory.getInstance(KEY_ALGORITHM).generateSecret(spec).getEncoded();
            return new SecretKeySpec(derived, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("암호화 키 유도에 실패했습니다.", e);
        }
    }

    /**
     * salt 를 키에서 결정적으로 유도한다.
     *
     * <p>salt 는 비밀이 아니지만 <b>재기동 후에도 같아야 한다.</b> 매번 임의 생성하면
     * 배포·재시작 시점에 기존 암호문을 전부 복호화할 수 없게 된다. 관리할 시크릿을
     * 하나 더 늘리지 않으려고 별도 설정값 대신 키 해시를 쓴다.
     */
    private static String deriveSalt(String encryptionKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
            // 8바이트면 충분하다. hex 문자열로 두는 것은 delux 시절 형식과 같게 유지하기 위해서다.
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
