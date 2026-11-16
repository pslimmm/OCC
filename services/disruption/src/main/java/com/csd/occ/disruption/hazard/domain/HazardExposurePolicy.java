package com.csd.occ.disruption.hazard.domain;

import java.util.List;
import java.util.Locale;

public final class HazardExposurePolicy {
    public List<Exposure> assess(HazardTransitPort.Bus bus, HazardTransitPort.Route route,
            List<HazardPollPort.Location> locations, double radiusKm) {
        var nextStopSequence = bus.nextStopSequence();
        if (nextStopSequence == null) return List.of();
        return route.operators().stream().flatMap(operator -> operator.directions().stream())
                .filter(direction -> direction.directionId() == bus.directionId())
                .flatMap(direction -> direction.stops().stream()).filter(stop -> stop.stopSequence() >= nextStopSequence)
                .flatMap(stop -> locations.stream().filter(location -> matches(location, stop, radiusKm))
                        .map(location -> new Exposure(bus.busId(), bus.serviceId(), bus.directionId(), stop.stopCode(),
                                stop.description(), nextStopSequence, stop.stopSequence(), location.geometryRole(),
                                "Candidate association; road path, direction relevance and operational impact are not verified.")))
                .distinct().toList();
    }
    private boolean matches(HazardPollPort.Location location, HazardTransitPort.Stop stop, double radiusKm) {
        if ("WORKSITE".equals(location.geometryRole())) return stop.stopCode().equals(location.sourceReference());
        if ("ROAD_NAME".equals(location.geometryRole()))
            return location.description() != null && stop.roadName() != null
                    && !location.description().isBlank() && location.description().strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)
                    .equals(stop.roadName().strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
        if (location.latitude() == null || location.longitude() == null || stop.latitude() == null || stop.longitude() == null) return false;
        double lat1 = location.latitude().doubleValue(), lat2 = stop.latitude().doubleValue();
        double a = Math.pow(Math.sin(Math.toRadians(lat2 - lat1) / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(Math.toRadians(stop.longitude().doubleValue() - location.longitude().doubleValue()) / 2), 2);
        double distance = 6371 * 2 * Math.asin(Math.sqrt(Math.clamp(a, 0, 1)));
        double limit = location.radiusKm() != null && ("BROADCAST_COVERAGE".equals(location.geometryRole())
                || "STATION_PROXIMITY".equals(location.geometryRole())) ? location.radiusKm().doubleValue() : radiusKm;
        return distance <= limit;
    }
    public record Exposure(String busId, String serviceId, int directionId, String stopCode, String stopName,
                           int busProgressSequence, int stopSequence, String basis, String limitation) {}
}
