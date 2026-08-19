package com.info.impaxo;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.content.Context;
import android.util.Log;

public class GunNsdManager {
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;

    public interface OnTargetFoundListener {
        void onTargetFound(String ipAddress, int port);
    }

    public void startDiscovery(Context context, OnTargetFoundListener listener) {
        stopDiscovery(); // Mevcut bir tarama varsa önce durdur
        nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                Log.d("ImpaxoLog", "NSD Taraması Başlatıldı...");
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                if (service.getServiceType().equals("_impaxo._tcp.") &&
                        service.getServiceName().contains("ImpaxoTarget")) {

                    nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {}

                        @Override
                        public void onServiceResolved(NsdServiceInfo serviceInfo) {
                            String targetIp = serviceInfo.getHost().getHostAddress();
                            int targetPort = serviceInfo.getPort();
                            Log.d("ImpaxoLog", "Hedef Bulundu! IP: " + targetIp + " Port: " + targetPort);

                            // IP bulundu, bağlantı otomatik tetikleniyor
                            listener.onTargetFound(targetIp, targetPort);
                        }
                    });
                }
            }

            @Override public void onDiscoveryStopped(String serviceType) {}
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {}
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
            @Override public void onServiceLost(NsdServiceInfo service) {}
        };

        nsdManager.discoverServices("_impaxo._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    public void stopDiscovery() {
        if (nsdManager != null && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception e) {
                Log.e("ImpaxoLog", "NSD Tarama durdurulamadı: " + e.getMessage());
            }
        }
    }
}