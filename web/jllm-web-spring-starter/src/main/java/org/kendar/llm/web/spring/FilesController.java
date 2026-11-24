package org.kendar.llm.web.spring;

import org.kendar.llm.web.commons.FilesApi;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** {@code /api/chat/{chatId}/fs/**}: binds the request, calls the {@link FilesApi}. */
@RestController
@RequestMapping("/api/chat/{chatId}/fs")
public class FilesController {

    private final FilesApi files;

    public FilesController(FilesApi files) {
        this.files = files;
    }

    @GetMapping
    public Map<String, Object> list(@PathVariable String chatId, @RequestParam(defaultValue = "") String path,
                                    @RequestParam(name = "root", defaultValue = "workspace") String rootName) throws IOException {
        return files.list(chatId, path, rootName);
    }

    @GetMapping("/content")
    public Map<String, Object> read(@PathVariable String chatId, @RequestParam String path,
                                    @RequestParam(name = "root", defaultValue = "workspace") String rootName) throws IOException {
        return files.read(chatId, path, rootName);
    }

    @PutMapping("/content")
    public Map<String, Object> write(@PathVariable String chatId, @RequestBody Map<String, String> body,
                                     @RequestParam(name = "root", defaultValue = "workspace") String rootName) throws IOException {
        return files.write(chatId, body, rootName);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@PathVariable String chatId, @RequestBody Map<String, String> body,
                                                      @RequestParam(name = "root", defaultValue = "workspace") String rootName) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(files.create(chatId, body, rootName));
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@PathVariable String chatId, @RequestParam(defaultValue = "") String path,
                                      @RequestParam("files") List<MultipartFile> parts,
                                      @RequestParam(defaultValue = "false") boolean overwrite,
                                      @RequestParam(name = "root", defaultValue = "workspace") String rootName) throws IOException {
        return files.upload(chatId, path, Requests.uploads(parts), overwrite, rootName);
    }

    @PostMapping("/rename")
    public Map<String, Object> rename(@PathVariable String chatId, @RequestBody Map<String, String> body,
                                      @RequestParam(name = "root", defaultValue = "workspace") String rootName) throws IOException {
        return files.rename(chatId, body, rootName);
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@PathVariable String chatId, @RequestParam String path,
                                       @RequestParam(name = "root", defaultValue = "workspace") String rootName) throws IOException {
        files.delete(chatId, path, rootName);
        return Responses.noContent();
    }

    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String chatId, @RequestParam String path,
                                                          @RequestParam(name = "root", defaultValue = "workspace") String rootName) throws IOException {
        return Responses.download(files.download(chatId, path, rootName));
    }

    @GetMapping("/glob")
    public List<String> glob(@PathVariable String chatId, @RequestParam String pattern) {
        return files.glob(chatId, pattern);
    }

    @GetMapping(value = "/tree", produces = MediaType.TEXT_PLAIN_VALUE)
    public String tree(@PathVariable String chatId, @RequestParam(defaultValue = "") String path,
                       @RequestParam(defaultValue = "2") int depth, @RequestParam(name = "root", defaultValue = "workspace") String rootName) {
        return files.tree(chatId, path, depth, rootName);
    }
}
