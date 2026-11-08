package com.cypherz01.kyb.api;

import com.cypherz01.kyb.check.CheckNotFoundException;
import com.cypherz01.kyb.check.CheckNotReplayableException;
import com.cypherz01.kyb.companieshouse.CompaniesHouseUnavailableException;
import com.cypherz01.kyb.companieshouse.CompanyNotFoundException;
import com.cypherz01.kyb.companieshouse.InvalidCompanyNumberException;
import com.cypherz01.kyb.matching.InvalidScreeningRequestException;
import com.cypherz01.kyb.matching.SanctionsDataUnavailableException;
import com.cypherz01.kyb.sanctions.ImportAlreadyRunningException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every API error is an RFC 7807 problem (spec §7). Spring's own MVC errors (unreadable body,
 * wrong method, ...) are handled by the base class, the domain errors here.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidCompanyNumberException.class)
    ProblemDetail invalidCompanyNumber(InvalidCompanyNumberException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid company number", e.getMessage());
    }

    @ExceptionHandler(CompanyNotFoundException.class)
    ProblemDetail companyNotFound(CompanyNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Company not found", e.getMessage());
    }

    /** Only reached when Companies House is down AND nothing (not even stale) is cached. */
    @ExceptionHandler(CompaniesHouseUnavailableException.class)
    ProblemDetail companiesHouseUnavailable(CompaniesHouseUnavailableException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Companies House unavailable", e.getMessage());
    }

    /** 503, not an empty result: "no matches" against no data would read as a clean result. */
    @ExceptionHandler(SanctionsDataUnavailableException.class)
    ProblemDetail sanctionsDataUnavailable(SanctionsDataUnavailableException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Sanctions data unavailable", e.getMessage());
    }

    @ExceptionHandler(InvalidScreeningRequestException.class)
    ProblemDetail invalidScreeningRequest(InvalidScreeningRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid screening request", e.getMessage());
    }

    @ExceptionHandler(ImportAlreadyRunningException.class)
    ProblemDetail importAlreadyRunning(ImportAlreadyRunningException e) {
        return problem(HttpStatus.CONFLICT, "Sanctions import already running", e.getMessage());
    }

    @ExceptionHandler(CheckNotFoundException.class)
    ProblemDetail checkNotFound(CheckNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Check not found", e.getMessage());
    }

    @ExceptionHandler(CheckNotReplayableException.class)
    ProblemDetail checkNotReplayable(CheckNotReplayableException e) {
        return problem(HttpStatus.CONFLICT, "Check cannot be replayed", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail argumentTypeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid request parameter",
                "'" + e.getValue() + "' is not a valid " + e.getName());
    }

    /** Last resort: log it, and return a problem that does not leak internals. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "An unexpected error occurred");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        return p;
    }
}
