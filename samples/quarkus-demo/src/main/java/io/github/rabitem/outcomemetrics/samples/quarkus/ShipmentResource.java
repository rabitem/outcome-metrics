package io.github.rabitem.outcomemetrics.samples.quarkus;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

@Path("/api/shipments")
@Produces(MediaType.APPLICATION_JSON)
public class ShipmentResource {

    @Inject
    ShipmentService shipmentService;

    @POST
    public Map<String, Object> dispatch(
            @QueryParam("orderId") final String orderId,
            @QueryParam("carrier") final String carrier) {
        return shipmentService.dispatch(orderId, carrier == null ? "dhl" : carrier);
    }

    @POST
    @Path("/label")
    public Map<String, Object> label(@QueryParam("orderId") final String orderId) {
        return shipmentService.printLabel(orderId);
    }

    @GET
    @Path("/delay-class")
    public Map<String, String> classify(@QueryParam("code") final String code) {
        return Map.of("classification", shipmentService.classifyDelay(code));
    }
}

