package org.leonardo.standardpay.support;

import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Um lojista de mentira, dentro da própria aplicação de teste: recebe os
 * webhooks e guarda o que chegou, com a assinatura, para as asserções.
 *
 * {@code ?status=500} faz ele responder com erro, para testar o reenvio.
 */
@Path("/test-sink")
public class WebhookSink {

    public record Received(String signature, String traceparent, String body) {
    }

    public static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();

    @POST
    public Response receive(@HeaderParam("StandardPay-Signature") String signature,
                            @HeaderParam("traceparent") String traceparent,
                            @QueryParam("status") Integer status,
                            String body) {
        RECEIVED.add(new Received(signature, traceparent, body));
        return Response.status(status == null ? 200 : status).build();
    }
}
