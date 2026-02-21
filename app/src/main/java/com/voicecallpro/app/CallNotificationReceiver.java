package com.voicecallpro.app;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public class CallNotificationReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (CallService.ACTION_END_CALL.equals(intent.getAction())) {
            Intent stopIntent = new Intent(context, CallService.class);
            stopIntent.setAction(CallService.ACTION_END_CALL);
            context.startService(stopIntent);
        }
    }
}
