package tech.orizon.ampara.audio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.ActivityCompat;

/**
 * Manages location tracking for audio recordings
 * Uses Android's native LocationManager API
 */
public class LocationManager {
    private static final String TAG = "LocationManager";
    private static final long UPDATE_INTERVAL_MS = 30000; // 30 seconds
    private static final long MIN_INTERVAL_MS = 1000; // 1 second for panic
    private static final float MIN_DISTANCE_METERS = 5; // Reduced to 5 meters for better resolution

    private final Context context;
    private final android.location.LocationManager systemLocationManager;
    private Location lastLocation;
    private LocationListener locationListener;
    private boolean isTracking = false;

    public LocationManager(Context context) {
        this.context = context;
        this.systemLocationManager = (android.location.LocationManager) context
                .getSystemService(Context.LOCATION_SERVICE);
    }

    /**
     * Start tracking location updates with default interval
     */
    public void startTracking() {
        startTracking(UPDATE_INTERVAL_MS);
    }

    /**
     * Start tracking location updates with custom interval
     */
    public void startTracking(long intervalMs) {

        if (isTracking) {
            Log.w(TAG, "Location tracking already started");
            return;
        }

        // Check permissions
        if (!hasLocationPermission()) {
            Log.e(TAG, "Location permission not granted");
            return;
        }

        try {
            // Create location listener
            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    // Update lastLocation but prioritize GPS over Network if they arrive close
                    // together
                    if (lastLocation == null ||
                            android.location.LocationManager.GPS_PROVIDER.equals(location.getProvider()) ||
                            (System.currentTimeMillis() - lastLocation.getTime() > 10000)) {
                        lastLocation = location;
                        Log.d(TAG, String.format("Location updated [%s]: %.6f, %.6f (accuracy: %.1fm)",
                                location.getProvider(), location.getLatitude(), location.getLongitude(),
                                location.getAccuracy()));
                    }
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                    Log.d(TAG, "Location provider enabled: " + provider);
                }

                @Override
                public void onProviderDisabled(String provider) {
                    Log.w(TAG, "Location provider disabled: " + provider);
                }
            };

            // Register GPS Provider (High Precision)
            if (systemLocationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                systemLocationManager.requestLocationUpdates(
                        android.location.LocationManager.GPS_PROVIDER,
                        intervalMs,
                        MIN_DISTANCE_METERS,
                        locationListener,
                        Looper.getMainLooper());
                Log.d(TAG, "GPS location tracking started (Fine Precision)");
            }

            // Register Network Provider (Indoor/Battery fallback)
            if (systemLocationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                systemLocationManager.requestLocationUpdates(
                        android.location.LocationManager.NETWORK_PROVIDER,
                        intervalMs,
                        MIN_DISTANCE_METERS,
                        locationListener,
                        Looper.getMainLooper());
                Log.d(TAG, "Network location tracking started (Coarse/Indoor Fallback)");
            }

            // Get last known location from any provider immediately
            Location gpsLast = systemLocationManager
                    .getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
            Location netLast = systemLocationManager
                    .getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);

            if (gpsLast != null && (netLast == null || gpsLast.getTime() > netLast.getTime() - 60000)) {
                lastLocation = gpsLast;
            } else if (netLast != null) {
                lastLocation = netLast;
            }

            isTracking = true;

            if (lastLocation != null) {
                Log.d(TAG, String.format("Initial location: %.6f, %.6f (provider=%s)",
                        lastLocation.getLatitude(), lastLocation.getLongitude(), lastLocation.getProvider()));
            } else {
                Log.w(TAG, "No initial location available from any provider");
            }

        } catch (SecurityException e) {
            Log.e(TAG, "Security exception starting location tracking", e);
        } catch (Exception e) {
            Log.e(TAG, "Error starting location tracking", e);
        }
    }

    /**
     * Stop tracking location updates
     */
    public void stopTracking() {
        if (!isTracking) {
            return;
        }

        try {
            if (locationListener != null) {
                systemLocationManager.removeUpdates(locationListener);
                locationListener = null;
            }
            isTracking = false;
            Log.d(TAG, "Location tracking stopped");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping location tracking", e);
        }
    }

    /**
     * Get last known location
     */
    public Location getLastLocation() {
        return lastLocation;
    }

    /**
     * Get latitude (0 if no location)
     */
    public double getLatitude() {
        return lastLocation != null ? lastLocation.getLatitude() : 0;
    }

    /**
     * Get longitude (0 if no location)
     */
    public double getLongitude() {
        return lastLocation != null ? lastLocation.getLongitude() : 0;
    }

    /**
     * Check if location permission is granted
     */
    private boolean hasLocationPermission() {
        return ActivityCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context,
                        Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Check if location tracking is active
     */
    public boolean isTracking() {
        return isTracking;
    }
}
