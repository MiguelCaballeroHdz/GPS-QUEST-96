package android.location;

/**
 * Stub mínimo de {@code android.location.Location} para los host tests.
 * Solo lo que usa `GeofenceManager`: lat/lon mutables y distancia esférica.
 */
public class Location {

    private final String provider;
    private double latitude;
    private double longitude;

    public Location(String provider) {
        this.provider = provider;
    }

    public String getProvider() {
        return provider;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    /** Haversine, radio terrestre medio 6 371 000 m. */
    public float distanceTo(Location other) {
        if (other == null) return Float.MAX_VALUE;
        double r = 6371000.0;
        double dLat = Math.toRadians(other.latitude - latitude);
        double dLon = Math.toRadians(other.longitude - longitude);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(other.latitude))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return (float) (2 * r * Math.asin(Math.min(1.0, Math.sqrt(a))));
    }
}
