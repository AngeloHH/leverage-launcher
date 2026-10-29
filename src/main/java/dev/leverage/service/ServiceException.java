package dev.leverage.service;

public final class ServiceException extends RuntimeException {
    private final int status;
    private final String code;

    public ServiceException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ServiceException badRequest(String code, String message) {
        return new ServiceException(400, code, message);
    }

    public static ServiceException unauthorized(String code, String message) {
        return new ServiceException(401, code, message);
    }

    public static ServiceException forbidden(String code, String message) {
        return new ServiceException(403, code, message);
    }

    public static ServiceException notFound(String code, String message) {
        return new ServiceException(404, code, message);
    }

    public static ServiceException conflict(String code, String message) {
        return new ServiceException(409, code, message);
    }
}
