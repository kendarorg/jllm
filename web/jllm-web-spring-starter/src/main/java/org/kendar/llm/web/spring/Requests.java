package org.kendar.llm.web.spring;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.web.commons.Cookies;
import org.springframework.web.multipart.MultipartFile;
import org.kendar.llm.web.commons.UploadedFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** What the wrappers read off a servlet request, as the plain values the handlers take. */
final class Requests {

    private Requests() {
    }

    /** The request's cookies; a blank value counts as absent. */
    static Cookies cookies(HttpServletRequest request) {
        return name -> {
            Cookie[] all = request.getCookies();
            if (all == null) return null;
            for (Cookie c : all) {
                if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) return c.getValue();
            }
            return null;
        };
    }

    /** The user the {@code /api/**} gate stored, if it did. */
    static Principal principal(HttpServletRequest request) {
        return request.getAttribute(Principal.ATTR) instanceof Principal p ? p : null;
    }

    static List<UploadedFile> uploads(List<MultipartFile> files) throws IOException {
        List<UploadedFile> out = new ArrayList<>();
        for (MultipartFile f : files) out.add(new UploadedFile(f.getOriginalFilename(), f.getSize(), f.getInputStream()));
        return out;
    }
}
