package com.example.att_gps_e_maps;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;

public final class NominatimUrl {

    public static final String BASE = "https://nominatim.openstreetmap.org";

    private NominatimUrl() { }

    public static String busca(String texto) {
        return BASE + "/search?format=jsonv2&limit=1&accept-language=pt-BR&q=" + codificar(texto);
    }

    public static String reversa(double latitude, double longitude) {
        return String.format(Locale.US,
                "%s/reverse?format=jsonv2&zoom=18&accept-language=pt-BR&lat=%.6f&lon=%.6f",
                BASE, latitude, longitude);
    }

    private static String codificar(String texto) {
        try {
            return URLEncoder.encode(texto, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
