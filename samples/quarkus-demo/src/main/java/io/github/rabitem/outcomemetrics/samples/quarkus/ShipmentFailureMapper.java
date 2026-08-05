package io.github.rabitem.outcomemetrics.samples.quarkus;

import io.github.rabitem.outcomemetrics.samples.quarkus.domain.ShipmentFailedException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public class ShipmentFailureMapper implements ExceptionMapper<ShipmentFailedException> {

    @Override
    public Response toResponse(final ShipmentFailedException exception) {
        return Response.status(422)
                .entity(Map.of(
                        "title", "Shipment failed",
                        "detail", exception.getMessage(),
                        "reason", exception.outcomeReason().code()))
                .build();
    }
}
