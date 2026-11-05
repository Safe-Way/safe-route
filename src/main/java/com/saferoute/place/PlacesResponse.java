package com.saferoute.place;

import java.util.List;

/** GET /api/places 응답: { "places": [ ... ] } */
public record PlacesResponse(List<Place> places) {
}
