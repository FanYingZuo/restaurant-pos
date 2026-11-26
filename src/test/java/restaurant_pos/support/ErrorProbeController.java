package restaurant_pos.support;

import java.sql.SQLException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;

/** 測試專用：丟出各種例外，驗證全域錯誤處理。只存在於 src/test。 */
@RestController
@RequestMapping("/api/test/errors")
public class ErrorProbeController {

    public record Body(String name) {
    }

    @GetMapping("/api")
    public String api(@RequestParam ErrorCode code) {
        throw new ApiException(code);
    }

    @GetMapping("/runtime")
    public String runtime() {
        throw new IllegalStateException("內部細節：資料庫密碼 hunter2 與 SQL 片段");
    }

    @GetMapping("/duplicate")
    public String duplicate() {
        throw new DataIntegrityViolationException("dup", new SQLException("Duplicate entry", "23000", 1062));
    }

    @GetMapping("/other-integrity")
    public String otherIntegrity() {
        throw new DataIntegrityViolationException("null", new SQLException("Column cannot be null", "23000", 1048));
    }

    @GetMapping("/lock-timeout")
    public String lockTimeout() {
        throw new CannotAcquireLockException("Lock wait timeout exceeded");
    }

    @GetMapping("/deadlock")
    public String deadlock() {
        throw new DeadlockLoserDataAccessException("Deadlock found", new SQLException("deadlock", "40001", 1213));
    }

    @GetMapping("/param")
    public String param(@RequestParam int n) {
        return "n=" + n;
    }

    @PostMapping(value = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
    public String body(@RequestBody Body body) {
        return body.name();
    }
}
