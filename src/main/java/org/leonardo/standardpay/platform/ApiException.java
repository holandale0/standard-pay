package org.leonardo.standardpay.platform;

/**
 * Recusa explicada: um status HTTP, um código estável para o programa do
 * lojista decidir o que fazer, e uma mensagem para quem está depurando.
 *
 * O código é o contrato; a mensagem pode mudar de redação a qualquer momento.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
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

    public static ApiException notFound(String what, String id) {
        return new ApiException(404, "resource_not_found", "No " + what + " with id '" + id + "'.");
    }

    public static ApiException invalid(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }
}
