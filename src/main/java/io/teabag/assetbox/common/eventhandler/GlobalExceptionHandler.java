package io.teabag.assetbox.common.eventhandler;

import io.teabag.assetbox.common.constants.ErrorCode;
import io.teabag.assetbox.common.dto.ApiResponse;
import io.teabag.assetbox.common.exception.BusinessException;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode code = exception.getErrorCode();
        return ResponseEntity
                .status(
                        code.getStatus()
                ).body(
                        ApiResponse.fail(
                                code.name(),
                                exception.getMessage())
                );
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(
            ObjectOptimisticLockingFailureException exception
    ) {
        ErrorCode code = ErrorCode.REQUEST_VERSION_CONFLICT;
        return ResponseEntity.status(code.getStatus())
                .body(ApiResponse.fail(code.name(), code.getDescription()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException exception
    ) {
        return ResponseEntity.
                badRequest().
                body(
                        ApiResponse.fail(
                                ErrorCode.VALIDATION_FAILED.name(),
                                ErrorCode.VALIDATION_FAILED.getDescription())
                );
    }
}
