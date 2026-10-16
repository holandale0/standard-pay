package org.leonardo.standardpay.platform;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.stream.Collectors;

/**
 * Todo erro da API sai no mesmo formato:
 *
 * <pre>{ "error": { "code": "amount_too_small", "message": "...", "requestId": "req_..." } }</pre>
 *
 * Um formato só é o que permite ao lojista tratar erros de forma genérica, em
 * vez de um caso por endpoint. O requestId é o que ele informa ao suporte: leva
 * direto às linhas de log daquela requisição.
 */
public class ErrorMappers {

    public record ErrorBody(Error error) {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Error(String code, String message, String requestId) {
        }

        public static ErrorBody of(String code, String message) {
            return new ErrorBody(new Error(code, message, RequestIds.current()));
        }
    }

    @ServerExceptionMapper
    public Response api(ApiException e) {
        return json(e.status(), e.code(), e.getMessage());
    }

    /** Falhas do Bean Validation: todas as mensagens juntas, para corrigir de uma vez. */
    @ServerExceptionMapper
    public Response validation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(v -> fieldOf(v.getPropertyPath().toString()) + ": " + v.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return json(400, "invalid_request", message);
    }

    /**
     * Corpo que nem é JSON — um texto cortado, ou fora de UTF-8. O Quarkus
     * embrulha o erro do Jackson numa WebApplicationException 400 sem corpo;
     * aqui ela ganha o formato padrão. As outras seguem como vieram.
     */
    @ServerExceptionMapper
    public Response webApplication(WebApplicationException e) {
        if (e.getCause() instanceof StreamReadException) {
            return json(400, "invalid_json", "The request body is not valid JSON (it must be UTF-8).");
        }
        return e.getResponse();
    }

    /** JSON válido com o tipo errado num campo: "amount": "abc". */
    @ServerExceptionMapper
    public Response mismatched(MismatchedInputException e) {
        String field = e.getPath().stream()
                .map(r -> r.getFieldName() != null ? r.getFieldName() : "[" + r.getIndex() + "]")
                .collect(Collectors.joining(".")).replace(".[", "[");
        return json(400, "invalid_request",
                (field.isEmpty() ? "The request body" : field) + " has the wrong type.");
    }

    /** "create.request.amount" vira "amount" — o caminho interno não diz nada ao lojista. */
    private static String fieldOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }

    private static Response json(int status, String code, String message) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorBody.of(code, message))
                .build();
    }
}
