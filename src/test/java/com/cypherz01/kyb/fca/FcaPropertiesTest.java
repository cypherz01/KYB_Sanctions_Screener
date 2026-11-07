package com.cypherz01.kyb.fca;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FcaPropertiesTest {

    @Test
    void credentialsNeedBothEmailAndKey() {
        assertThat(FcaTestProperties.with("http://x", "a@b.com", "key").hasCredentials())
                .isTrue();
        assertThat(FcaTestProperties.with("http://x", "a@b.com", " ").hasCredentials())
                .isFalse();
        assertThat(FcaTestProperties.with("http://x", null, "key").hasCredentials())
                .isFalse();
    }

    @Test
    void toStringNeverShowsCredentials() {
        String s = FcaTestProperties.with("http://x", "someone@example.com", "secret-key-123")
                .toString();

        assertThat(s).doesNotContain("secret-key-123").doesNotContain("someone@example.com");
    }
}
