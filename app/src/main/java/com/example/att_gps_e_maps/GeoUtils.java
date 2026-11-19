package com.example.att_gps_e_maps;

import java.util.Locale;

public final class GeoUtils {

    private static final double RAIO_TERRA_KM = 6371.0088;

    private GeoUtils() { }

    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * RAIO_TERRA_KM * Math.asin(Math.sqrt(h));
    }

    public static boolean coordenadasValidas(double lat, double lng) {
        return !Double.isNaN(lat) && !Double.isNaN(lng)
                && lat >= -90 && lat <= 90
                && lng >= -180 && lng <= 180;
    }

    public static boolean dentroDaBahia(double lat, double lng) {
        return coordenadasValidas(lat, lng)
                && lat >= -18.4 && lat <= -8.5
                && lng >= -46.7 && lng <= -37.3;
    }

    public static String formatarCoordenada(double valor) {
        return String.format(Locale.US, "%.6f", valor);
    }

    public static String formatarDistancia(double km) {
        if (km < 1) {
            return Math.round(km * 1000) + " m";
        }
        return String.format(Locale.forLanguageTag("pt-BR"), "%.2f km", km);
    }

    public static String paraGms(double valor, boolean isLatitude) {
        char hemisferio = isLatitude ? (valor < 0 ? 'S' : 'N') : (valor < 0 ? 'O' : 'L');
        double abs = Math.abs(valor);
        int graus = (int) abs;
        double restoMin = (abs - graus) * 60.0;
        int minutos = (int) restoMin;
        double segundos = (restoMin - minutos) * 60.0;
        return String.format(Locale.forLanguageTag("pt-BR"), "%d°%02d'%.2f\" %c", graus, minutos, segundos, hemisferio);
    }
}
