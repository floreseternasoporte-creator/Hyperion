package com.drex.hyperion.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * PlanBootReceiver — al encender el teléfono, pliega el consumo medido antes
 * del apagado en el acumulado del ciclo para no perder la cuenta del plan.
 */
public class PlanBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            DataPlan.onBoot(context);
        }
    }
}
