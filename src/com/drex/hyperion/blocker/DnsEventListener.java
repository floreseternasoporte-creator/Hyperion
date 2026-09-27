package com.drex.hyperion.blocker;

/**
 * Contrato de eventos DNS que el worker de VPN debe invocar.
 *
 * <p>El worker de VPN implementa en {@code HyperionVpnService} el método
 * estático {@code setDnsEventListener(DnsEventListener)} y llama a
 * {@link #onDnsEvent} por cada consulta DNS procesada por el túnel local,
 * indicando si fue bloqueada y con qué categoría (según
 * {@link AdBlocker#categoryOf}).</p>
 */
public interface DnsEventListener {

    /**
     * @param domain   dominio consultado (normalizado, sin punto final)
     * @param uid      UID de la app que originó la consulta (para stats por app)
     * @param blocked  true si la consulta fue bloqueada por el escudo
     * @param category categoría de {@link AdBlocker#categoryOf} ("ads", "tracker",
     *                 "malware", "phishing", "miner"); null si fue permitida
     */
    void onDnsEvent(String domain, int uid, boolean blocked, String category);
}
