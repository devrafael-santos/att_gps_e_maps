package com.example.att_gps_e_maps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.CopyrightOverlay;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends AppCompatActivity implements LocationListener {

    private static final int REQ_LOCALIZACAO = 1001;
    private static final double CENTRO_LAT = -12.9714;
    private static final double CENTRO_LNG = -38.5014;

    private static class PontoNoMapa {
        final PontoMapa ponto;
        Marker marcador;
        Polygon circulo1Km;

        PontoNoMapa(PontoMapa ponto) {
            this.ponto = ponto;
        }
    }

    private MapView mapa;
    private TextView tvStatus;
    private TextView tvResumo;
    private EditText etBusca;
    private Button btnAcompanhar;

    private LocationManager locationManager;
    private NominatimClient nominatim;
    private LocalDbHelper banco;

    private final List<PontoNoMapa> pontos = new ArrayList<>();
    private final List<GeoPoint> trajetoAcompanhado = new ArrayList<>();
    private Polyline linha;
    private Polyline linhaTrajetoGps;
    private Marker marcadorGps;
    private Polygon circuloPrecisao;

    private boolean acompanhando = false;
    private boolean primeiroFix = true;
    private boolean pendenteContinuo = false;
    private Location ultimaPosicao;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        File cacheAntigo1 = new File(getCacheDir(), "osmdroid");
        File cacheAntigo2 = new File(getCacheDir(), "osmdroid_v2");
        File base = new File(getCacheDir(), "osmdroid_limpo");
        apagarPasta(cacheAntigo1);
        apagarPasta(cacheAntigo2);
        apagarPasta(base);

        IConfigurationProvider config = Configuration.getInstance();
        config.load(getApplicationContext(), getSharedPreferences("osmdroid_limpo", MODE_PRIVATE));
        config.getAdditionalHttpRequestProperties().clear();
        config.setUserAgentValue("br.edu.aula.gpsmapas/1.0");
        config.setOsmdroidBasePath(base);
        config.setOsmdroidTileCache(new File(base, "tiles"));

        setContentView(R.layout.activity_main);
        aplicarMargensDoSistema(findViewById(R.id.raiz));

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        nominatim = new NominatimClient();
        banco = new LocalDbHelper(this);

        tvStatus = findViewById(R.id.tvStatus);
        tvResumo = findViewById(R.id.tvResumo);
        etBusca = findViewById(R.id.etBusca);
        btnAcompanhar = findViewById(R.id.btnAcompanhar);

        configurarMapa();
        adicionarMarcadoresIniciais();

        findViewById(R.id.btnLocalizar).setOnClickListener(v -> iniciarLocalizacao(false));
        btnAcompanhar.setOnClickListener(v -> alternarAcompanhamento());
        findViewById(R.id.btnBuscar).setOnClickListener(v -> buscarEndereco());
        findViewById(R.id.btnSalvar).setOnClickListener(v -> salvarPontos());
        findViewById(R.id.btnVerSalvos).setOnClickListener(v -> mostrarSalvos());
        findViewById(R.id.btnSql).setOnClickListener(v -> mostrarSqlEProximos());
        findViewById(R.id.btnLimpar).setOnClickListener(v -> limparPontos());
        etBusca.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                buscarEndereco();
                return true;
            }
            return false;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapa.onResume();
        if (acompanhando) {
            registrarListeners();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        locationManager.removeUpdates(this);
        mapa.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        nominatim.encerrar();
        banco.close();
        mapa.onDetach();
    }

    private void configurarMapa() {
        mapa = findViewById(R.id.map);
        mapa.setTileSource(TileSourceFactory.MAPNIK);
        mapa.getTileProvider().clearTileCache();
        mapa.setMultiTouchControls(true);
        mapa.getController().setZoom(13.0);
        mapa.getController().setCenter(new GeoPoint(CENTRO_LAT, CENTRO_LNG));

        mapa.getOverlays().add(new CopyrightOverlay(this));

        mapa.getOverlays().add(0, new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                adicionarPonto(p.getLatitude(), p.getLongitude(), null, true);
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                return false;
            }
        }));

        linha = new Polyline();
        linha.getOutlinePaint().setColor(0xFF1F4E79);
        linha.getOutlinePaint().setStrokeWidth(8f);
        mapa.getOverlays().add(linha);

        linhaTrajetoGps = new Polyline();
        linhaTrajetoGps.getOutlinePaint().setColor(0xFF1B7A3D);
        linhaTrajetoGps.getOutlinePaint().setStrokeWidth(6f);
        mapa.getOverlays().add(linhaTrajetoGps);
    }

    private void adicionarMarcadoresIniciais() {
        adicionarPonto(-12.9714, -38.5124, "Pelourinho", false);
        adicionarPonto(-12.9730, -38.5136, "Elevador Lacerda", false);
        adicionarPonto(-13.0105, -38.5326, "Farol da Barra", false);
    }

    private void adicionarPonto(double lat, double lng, String nome) {
        adicionarPonto(lat, lng, nome, false);
    }

    private void adicionarPonto(double lat, double lng, String nome, boolean desenharRaio1Km) {
        final PontoNoMapa pn = new PontoNoMapa(new PontoMapa(
                nome != null ? nome : "Ponto " + (pontos.size() + 1), lat, lng));

        GeoPoint posicao = new GeoPoint(lat, lng);

        if (desenharRaio1Km) {
            Polygon circulo = new Polygon();
            circulo.getFillPaint().setColor(0x181F4E79);
            circulo.getOutlinePaint().setColor(0xFF1F4E79);
            circulo.getOutlinePaint().setStrokeWidth(2f);
            circulo.setPoints(Polygon.pointsAsCircle(posicao, 1000.0));
            mapa.getOverlays().add(circulo);
            pn.circulo1Km = circulo;
        }

        Marker m = new Marker(mapa);
        m.setPosition(posicao);
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        m.setTitle(pn.ponto.nome);
        m.setSnippet(GeoUtils.formatarCoordenada(lat) + ", " + GeoUtils.formatarCoordenada(lng)
                + "\n" + GeoUtils.paraGms(lat, true) + ", " + GeoUtils.paraGms(lng, false));
        m.setDraggable(true);
        m.setOnMarkerDragListener(new Marker.OnMarkerDragListener() {
            @Override
            public void onMarkerDrag(Marker marker) { }

            @Override
            public void onMarkerDragEnd(Marker marker) {
                GeoPoint novaPos = marker.getPosition();
                pn.ponto.latitude = novaPos.getLatitude();
                pn.ponto.longitude = novaPos.getLongitude();
                marker.setSnippet(GeoUtils.formatarCoordenada(pn.ponto.latitude) + ", "
                        + GeoUtils.formatarCoordenada(pn.ponto.longitude));
                if (pn.circulo1Km != null) {
                    pn.circulo1Km.setPoints(Polygon.pointsAsCircle(novaPos, 1000.0));
                }
                atualizarResumo();
                buscarReversa(pn);
            }

            @Override
            public void onMarkerDragStart(Marker marker) { }
        });

        mapa.getOverlays().add(m);
        pn.marcador = m;
        pontos.add(pn);
        atualizarResumo();
        mapa.invalidate();

        if (nome == null) {
            status("Ponto marcado (raio de 1 km). Buscando o endereço...", false);
            buscarReversa(pn);
        }
    }

    private void buscarReversa(final PontoNoMapa pn) {
        nominatim.reversa(pn.ponto.latitude, pn.ponto.longitude, new NominatimClient.Callback<String>() {
            @Override
            public void onSucesso(String endereco) {
                if (isFinishing() || isDestroyed()) return;
                pn.ponto.nome = endereco;
                pn.marcador.setTitle(endereco);
                atualizarResumo();
                status("Endereço: " + endereco, false);
            }

            @Override
            public void onErro(String mensagem) {
                if (isFinishing() || isDestroyed()) return;
                status(mensagem, true);
            }
        });
    }

    private void atualizarResumo() {
        List<GeoPoint> trajeto = new ArrayList<>();
        StringBuilder texto = new StringBuilder();
        double total = 0;

        for (int i = 0; i < pontos.size(); i++) {
            PontoMapa p = pontos.get(i).ponto;
            trajeto.add(new GeoPoint(p.latitude, p.longitude));
            texto.append(i + 1).append(". ").append(p.nome).append('\n')
                 .append("    ").append(GeoUtils.formatarCoordenada(p.latitude)).append(", ")
                 .append(GeoUtils.formatarCoordenada(p.longitude));
            if (i > 0) {
                PontoMapa anterior = pontos.get(i - 1).ponto;
                double d = GeoUtils.haversineKm(anterior.latitude, anterior.longitude, p.latitude, p.longitude);
                total += d;
                texto.append("  (+").append(GeoUtils.formatarDistancia(d)).append(")");
            }
            if (ultimaPosicao != null) {
                double distGps = GeoUtils.haversineKm(
                        ultimaPosicao.getLatitude(), ultimaPosicao.getLongitude(),
                        p.latitude, p.longitude);
                texto.append("  [GPS: ").append(GeoUtils.formatarDistancia(distGps)).append("]");
            }
            texto.append('\n');
        }
        if (pontos.size() >= 2) {
            texto.append("Distância total em linha reta: ").append(GeoUtils.formatarDistancia(total));
        }

        linha.setPoints(trajeto);
        tvResumo.setText(pontos.isEmpty() ? getString(R.string.resumo_vazio) : texto.toString());
        mapa.invalidate();
    }

    private void limparPontos() {
        for (PontoNoMapa pn : pontos) {
            mapa.getOverlays().remove(pn.marcador);
            if (pn.circulo1Km != null) {
                mapa.getOverlays().remove(pn.circulo1Km);
            }
        }
        pontos.clear();
        atualizarResumo();
        status("Pontos removidos do mapa.", false);
    }

    private boolean temPermissaoFina() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean temPermissao() {
        return temPermissaoFina() || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void iniciarLocalizacao(boolean continuo) {
        if (!temPermissao()) {
            pendenteContinuo = continuo;
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCALIZACAO);
            return;
        }
        boolean gps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) && temPermissaoFina();
        boolean rede = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        if (!gps && !rede) {
            status("Localização desligada (ou só aproximada, sem GPS). Ative a localização do aparelho.", true);
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        acompanhando = continuo;
        primeiroFix = true;
        if (continuo) {
            trajetoAcompanhado.clear();
            linhaTrajetoGps.setPoints(trajetoAcompanhado);
        }
        btnAcompanhar.setText(continuo ? "Parar" : getString(R.string.btn_acompanhar));
        status("Obtendo sua posição...", false);
        registrarListeners();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCALIZACAO) {
            if (temPermissao()) {
                iniciarLocalizacao(pendenteContinuo);
            } else {
                status("Permissão de localização negada. Libere nas configurações do app.", true);
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void registrarListeners() {
        locationManager.removeUpdates(this);
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) && temPermissaoFina()) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, this);
            }
        } catch (SecurityException e) {
            status("Sem permissão para acessar a localização.", true);
        }
    }

    private void alternarAcompanhamento() {
        if (acompanhando) {
            acompanhando = false;
            locationManager.removeUpdates(this);
            trajetoAcompanhado.clear();
            linhaTrajetoGps.setPoints(trajetoAcompanhado);
            mapa.invalidate();
            btnAcompanhar.setText(getString(R.string.btn_acompanhar));
            status("Acompanhamento encerrado.", false);
        } else {
            iniciarLocalizacao(true);
        }
    }

    @Override
    public void onLocationChanged(Location loc) {
        ultimaPosicao = loc;
        GeoPoint gp = new GeoPoint(loc.getLatitude(), loc.getLongitude());

        if (marcadorGps == null) {
            circuloPrecisao = new Polygon();
            circuloPrecisao.getFillPaint().setColor(0x222563EB);
            circuloPrecisao.getOutlinePaint().setColor(0xFF2563EB);
            circuloPrecisao.getOutlinePaint().setStrokeWidth(2f);
            mapa.getOverlays().add(circuloPrecisao);

            GradientDrawable bolinha = new GradientDrawable();
            bolinha.setShape(GradientDrawable.OVAL);
            bolinha.setColor(0xFF2563EB);
            bolinha.setStroke(4, Color.WHITE);
            bolinha.setSize(42, 42);

            marcadorGps = new Marker(mapa);
            marcadorGps.setIcon(bolinha);
            marcadorGps.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            marcadorGps.setTitle("Você está aqui");
            mapa.getOverlays().add(marcadorGps);
        }
        marcadorGps.setPosition(gp);
        circuloPrecisao.setPoints(Polygon.pointsAsCircle(gp, loc.getAccuracy()));

        if (acompanhando) {
            trajetoAcompanhado.add(gp);
            linhaTrajetoGps.setPoints(trajetoAcompanhado);
        }

        if (primeiroFix) {
            mapa.getController().setZoom(17.0);
            mapa.getController().animateTo(gp);
            primeiroFix = false;
        }
        atualizarResumo();

        StringBuilder msg = new StringBuilder("Você está aqui (" + loc.getProvider() + "):\n")
                .append(GeoUtils.formatarCoordenada(loc.getLatitude())).append(", ")
                .append(GeoUtils.formatarCoordenada(loc.getLongitude()))
                .append("  |  precisão ±").append(Math.round(loc.getAccuracy())).append(" m");
        if (loc.hasSpeed()) {
            msg.append("  |  ").append(String.format(java.util.Locale.US, "%.1f", loc.getSpeed() * 3.6)).append(" km/h");
        }
        if (!acompanhando) {
            boolean boa = LocationManager.GPS_PROVIDER.equals(loc.getProvider()) || loc.getAccuracy() <= 50;
            if (boa) {
                locationManager.removeUpdates(this);
            } else {
                msg.append("\n(aguardando o GPS para melhorar a precisão...)");
            }
        }
        status(msg.toString(), false);
    }

    @Override
    public void onProviderEnabled(String provider) { }

    @Override
    public void onProviderDisabled(String provider) {
        status("Provedor de localização desativado: " + provider, true);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) { }

    private void buscarEndereco() {
        String texto = etBusca.getText().toString().trim();
        if (texto.isEmpty()) {
            status("Digite um endereço ou lugar para buscar.", true);
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(etBusca.getWindowToken(), 0);

        status("Buscando \"" + texto + "\"...", false);
        nominatim.buscar(texto, new NominatimClient.Callback<NominatimClient.Resultado>() {
            @Override
            public void onSucesso(NominatimClient.Resultado r) {
                if (isFinishing() || isDestroyed()) return;
                adicionarPonto(r.latitude, r.longitude, r.nome, true);
                mapa.getController().setZoom(16.0);
                mapa.getController().animateTo(new GeoPoint(r.latitude, r.longitude));
                status("Encontrado: " + r.nome, false);
            }

            @Override
            public void onErro(String mensagem) {
                if (isFinishing() || isDestroyed()) return;
                status(mensagem, true);
            }
        });
    }

    private void salvarPontos() {
        if (pontos.isEmpty()) {
            status("Marque pelo menos um ponto antes de salvar.", true);
            return;
        }
        List<PontoMapa> lista = new ArrayList<>();
        for (PontoNoMapa pn : pontos) {
            if (!GeoUtils.coordenadasValidas(pn.ponto.latitude, pn.ponto.longitude)) {
                status("Coordenada inválida: " + pn.ponto.nome, true);
                return;
            }
            lista.add(pn.ponto);
        }
        try {
            int n = banco.inserirTodos(lista);
            status(n + " ponto(s) salvo(s) no banco SQLite do aparelho.", false);
        } catch (RuntimeException e) {
            status("Erro ao salvar: " + e.getMessage(), true);
        }
    }

    private void mostrarSalvos() {
        final List<PontoMapa> salvos = banco.listar();
        if (salvos.isEmpty()) {
            Toast.makeText(this, "Nenhum ponto salvo.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] itens = new String[salvos.size()];
        for (int i = 0; i < itens.length; i++) {
            PontoMapa p = salvos.get(i);
            itens[i] = p.id + ". " + p.nome + "\n" + GeoUtils.formatarCoordenada(p.latitude)
                    + ", " + GeoUtils.formatarCoordenada(p.longitude);
        }
        new AlertDialog.Builder(this)
                .setTitle("Pontos salvos (" + salvos.size() + ")")
                .setItems(itens, null)
                .setPositiveButton("Fechar", null)
                .setNeutralButton("Apagar todos", (dialogo, qual) -> {
                    int apagados = banco.apagarTodos();
                    status(apagados + " ponto(s) apagado(s) do banco.", false);
                })
                .show();
    }

    private void mostrarSqlEProximos() {
        if (pontos.isEmpty()) {
            status("Marque pelo menos um ponto para gerar o SQL.", true);
            return;
        }

        StringBuilder sql = new StringBuilder();
        sql.append("-- Restrição para a Bahia (Atividade 7):\n")
           .append("-- ALTER TABLE locais ADD CONSTRAINT chk_bahia\n")
           .append("--   CHECK (latitude BETWEEN -18.4 AND -8.5 AND longitude BETWEEN -46.7 AND -37.3);\n\n")
           .append("INSERT INTO locais (nome, latitude, longitude) VALUES\n");

        for (int i = 0; i < pontos.size(); i++) {
            PontoMapa p = pontos.get(i).ponto;
            String nomeEscapado = "'" + p.nome.replace("'", "''") + "'";
            sql.append("  (").append(nomeEscapado).append(", ")
               .append(GeoUtils.formatarCoordenada(p.latitude)).append(", ")
               .append(GeoUtils.formatarCoordenada(p.longitude)).append(")");
            sql.append(i < pontos.size() - 1 ? ",\n" : ";\n");
        }

        if (pontos.size() >= 2) {
            final PontoMapa origem = pontos.get(0).ponto;
            List<PontoMapa> candidatos = new ArrayList<>();
            for (int i = 1; i < pontos.size(); i++) {
                candidatos.add(pontos.get(i).ponto);
            }
            Collections.sort(candidatos, Comparator.comparingDouble(
                    p -> GeoUtils.haversineKm(origem.latitude, origem.longitude, p.latitude, p.longitude)));

            sql.append("\n-- Mais próximos de ").append(origem.nome).append(" (Atividade 6):\n");
            for (int i = 0; i < Math.min(2, candidatos.size()); i++) {
                PontoMapa c = candidatos.get(i);
                double km = GeoUtils.haversineKm(origem.latitude, origem.longitude, c.latitude, c.longitude);
                sql.append("-- ").append(i + 1).append(". ").append(c.nome)
                   .append(" (").append(GeoUtils.formatarDistancia(km)).append(")\n");
            }
        }

        final String textoSql = sql.toString();
        new AlertDialog.Builder(this)
                .setTitle("SQL para o PostgreSQL")
                .setMessage(textoSql)
                .setPositiveButton("Copiar", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("SQL", textoSql));
                        Toast.makeText(this, "SQL copiado!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void status(String mensagem, boolean erro) {
        tvStatus.setText(mensagem);
        tvStatus.setTextColor(erro ? 0xFFB42318 : 0xFF1D2733);
    }

    private void apagarPasta(File arquivoOuPasta) {
        if (arquivoOuPasta == null || !arquivoOuPasta.exists()) return;
        File[] filhos = arquivoOuPasta.listFiles();
        if (filhos != null) {
            for (File f : filhos) {
                apagarPasta(f);
            }
        }
        arquivoOuPasta.delete();
    }

    private void aplicarMargensDoSistema(View raiz) {
        raiz.setOnApplyWindowInsetsListener((v, insets) -> {
            int topo;
            int base;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets barras = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                topo = barras.top;
                base = barras.bottom;
                v.setPadding(barras.left, topo, barras.right, base);
                return WindowInsets.CONSUMED;
            }
            topo = insets.getSystemWindowInsetTop();
            base = insets.getSystemWindowInsetBottom();
            v.setPadding(insets.getSystemWindowInsetLeft(), topo,
                    insets.getSystemWindowInsetRight(), base);
            return insets.consumeSystemWindowInsets();
        });
    }
}
