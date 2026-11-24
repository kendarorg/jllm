package org.kendar.llm.web.spring;

import org.kendar.llm.web.commons.ByteStream;
import org.kendar.llm.web.commons.CookieOut;
import org.kendar.llm.web.commons.Reply;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

/** Handler results as the framework's responses. */
final class Responses {

    private Responses() {
    }

    static <T> ResponseEntity<T> entity(Reply<T> reply) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(reply.status());
        reply.headers().forEach(b::header);
        for (CookieOut c : reply.cookies()) b.header(HttpHeaders.SET_COOKIE, cookie(c).toString());
        return reply.body() == null ? b.build() : b.body(reply.body());
    }

    static ResponseCookie cookie(CookieOut c) {
        return ResponseCookie.from(c.name(), c.value()).path(c.path()).sameSite(c.sameSite()).httpOnly(c.httpOnly())
                .secure(c.secure()).maxAge(c.maxAge()).build();
    }

    /** 204 without a body. */
    static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().build();
    }

    static ResponseEntity<StreamingResponseBody> download(ByteStream stream) {
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(MediaType.parseMediaType(stream.contentType()));
        if (stream.filename() != null) {
            b.header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(stream.filename(), StandardCharsets.UTF_8).build().toString());
        }
        if (stream.length() != null) b.contentLength(stream.length());
        return b.body(stream.writer()::writeTo);
    }
}
