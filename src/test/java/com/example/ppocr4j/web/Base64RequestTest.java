package com.example.ppocr4j.web;

import com.example.ppocr4j.exception.OcrException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base64RequestTest {

    @Test
    void decodesPlainBase64() {
        byte[] raw = {1, 2, 3, 4};
        var req = new Base64Request(Base64.getEncoder().encodeToString(raw), null, null, null);
        assertThat(req.toBytes()).isEqualTo(raw);
    }

    @Test
    void decodesDataUrl() {
        byte[] raw = {9, 8, 7};
        var req = new Base64Request("data:image/png;base64," + Base64.getEncoder().encodeToString(raw),
                null, null, null);
        assertThat(req.toBytes()).isEqualTo(raw);
    }

    @Test
    void rejectsBlankImage() {
        var req = new Base64Request("  ", null, null, null);
        assertThatThrownBy(req::toBytes)
                .isInstanceOf(OcrException.class)
                .extracting(e -> ((OcrException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAM);
    }

    @Test
    void rejectsInvalidBase64() {
        var req = new Base64Request("!!not-base64!!", null, null, null);
        assertThatThrownBy(req::toBytes)
                .isInstanceOf(OcrException.class)
                .extracting(e -> ((OcrException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAM);
    }

    @Test
    void defaultsForOptionalFields() {
        var req = new Base64Request("aGk=", null, null, null);
        assertThat(req.rotateOrDefault()).isZero();
        assertThat(req.autoRotateOrDefault()).isFalse();
    }
}
