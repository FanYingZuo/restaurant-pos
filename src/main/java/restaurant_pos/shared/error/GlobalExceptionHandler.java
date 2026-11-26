package restaurant_pos.shared.error;

import java.sql.SQLException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全域錯誤處理（§4.8）：業務錯誤轉成錯誤碼；框架錯誤對應 VALIDATION_ERROR／NOT_FOUND／405／415；
 * 唯一索引衝突、死結、鎖等待逾時、樂觀鎖衝突一律轉 CONCURRENT_MODIFICATION；其餘未預期例外回 INTERNAL_ERROR。
 * 回應不含堆疊與例外訊息；細節只寫日誌（日誌也不記請求內容，避免機密外洩）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final int MYSQL_DUPLICATE_KEY = 1062;

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode code) {
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, clock.instant()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        log.info("業務錯誤：{}", e.code());
        return respond(e.code());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            ServletRequestBindingException.class})
    public ResponseEntity<ErrorResponse> handleValidation(Exception e) {
        log.info("請求格式錯誤：{}", e.getClass().getSimpleName());
        return respond(ErrorCode.VALIDATION_ERROR);
    }

    /** 目前只有菜單圖片上傳是 multipart，超過上傳上限視為不合法的圖片（§4.10）。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return respond(ErrorCode.INVALID_IMAGE);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception e) {
        return respond(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException e) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler({HttpMediaTypeNotSupportedException.class, HttpMediaTypeNotAcceptableException.class})
    public ResponseEntity<ErrorResponse> handleMediaType(Exception e) {
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @ExceptionHandler({PessimisticLockingFailureException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> handleLockConflict(Exception e) {
        log.warn("鎖衝突、死結或鎖等待逾時：{}", e.getClass().getSimpleName());
        return respond(ErrorCode.CONCURRENT_MODIFICATION);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException e) {
        if (isDuplicateKey(e)) {
            log.warn("唯一索引衝突");
            return respond(ErrorCode.CONCURRENT_MODIFICATION);
        }
        log.error("資料完整性錯誤：{}", e.getClass().getSimpleName());
        return respond(ErrorCode.INTERNAL_ERROR);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // 例外堆疊只寫日誌，不進回應
        log.error("未預期的例外", e);
        return respond(ErrorCode.INTERNAL_ERROR);
    }

    private static boolean isDuplicateKey(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() == MYSQL_DUPLICATE_KEY) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return e instanceof org.springframework.dao.DuplicateKeyException;
    }
}
