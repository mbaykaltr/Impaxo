package com.info.impaxo;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.content.Context;
import android.util.Log;

public class TargetNsdManager {
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;

    public void registerService(Context context, int port) {
        stopService(); // Mevcut bir yayın varsa önce durdur
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName("ImpaxoTarget");
        serviceInfo.setServiceType("_impaxo._tcp.");
        serviceInfo.setPort(port);

        nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo NsdServiceInfo) {
                Log.d("ImpaxoLog", "NSD Yayını Başlatıldı: " + NsdServiceInfo.getServiceName());
            }

            @Override public void onRegistrationFailed(NsdServiceInfo arg0, int arg1) {}
            @Override public void onServiceUnregistered(NsdServiceInfo arg0) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo arg0, int arg1) {}
        };

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
    }

    public void stopService() {
        if (nsdManager != null && registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (Exception e) {
                Log.e("ImpaxoLog", "NSD Kapatılamadı: " + e.getMessage());
            }
        }
    }
}