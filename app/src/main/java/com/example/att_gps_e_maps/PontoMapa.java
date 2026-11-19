package com.example.att_gps_e_maps;

public class PontoMapa {
    public long id;
    public String nome;
    public double latitude;
    public double longitude;

    public PontoMapa(String nome, double latitude, double longitude) {
        this.nome = nome;
        this.latitude = latitude;
        this.longitude = longitude;
    }
}
