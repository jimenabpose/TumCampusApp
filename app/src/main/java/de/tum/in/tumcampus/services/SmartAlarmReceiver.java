package de.tum.in.tumcampus.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import de.tum.in.tumcampus.auxiliary.AlarmSchedulerTask;
import de.tum.in.tumcampus.auxiliary.Const;
import de.tum.in.tumcampus.auxiliary.DateUtils;
import de.tum.in.tumcampus.auxiliary.SmartAlarmUtils;
import de.tum.in.tumcampus.auxiliary.Utils;
import de.tum.in.tumcampus.models.SmartAlarmInfo;

public class SmartAlarmReceiver extends BroadcastReceiver {
    public static final int PRE_ALARM_DIFF = 1;

    public static final String INFO = "INFO";

    public static final String ACTION_TOGGLE = "de.tum.in.tumcampus.widget.SmartAlarmWidgetProvider.action.TOGGLE";
    public static final String ACTION_ALARM = "de.tum.in.tumcampus.widget.SmartAlarmWidgetProvider.action.ALARM";
    public static final String ACTION_PREALARM = "de.tum.in.tumcampus.widget.SmartAlarmWidgetProvider.action.PREALARM";
    public static final String ACTION_RETRY = "de.tum.in.tumcampus.widget.SmartAlarmWidgetProvider.action.RETRY";;

    @Override
    public void onReceive(Context context, Intent intent) {
        Utils.log("SmartAlarm Broadcast received: " + intent.getAction());
        switch (intent.getAction()) {
            case ACTION_ALARM:
                handleAlarm(context, intent);
                break;

            case ACTION_PREALARM:
                handlePreAlarm(context, intent);
                break;

            case ACTION_RETRY:
                SmartAlarmUtils.scheduleAlarm(context);
                break;

            case ACTION_TOGGLE:
                handleToggle(context);
                break;

            // after reboot / application update: restart alarm service, if activated
            case Intent.ACTION_PACKAGE_REPLACED:
            case Intent.ACTION_BOOT_COMPLETED:
                if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(Const.SMART_ALARM_ACTIVE, false)) {
                    SmartAlarmUtils.scheduleAlarm(context);
                }
                break;
        }
    }

    /**
     * Recalculate public transport route in case of delays etc.
     * @param c Context
     * @param intent Intent containing a SmartAlarmInfo object in extra field INFO
     */
    private void handlePreAlarm(Context c, Intent intent) {
        SmartAlarmInfo sai = (SmartAlarmInfo) intent.getExtras().get(INFO);

        new AlarmSchedulerTask(c, sai).execute();
    }

    /**
     * Displays alarm on screen and vibrates phone
     * @param c Context
     * @param intent Intent containing a SmartAlarmInfo object in extra field INFO
     */
    private void handleAlarm(Context c, Intent intent) {
        SmartAlarmInfo sai = (SmartAlarmInfo) intent.getExtras().get(INFO);

        Intent showAlert = new Intent(c, SmartAlarmService.class);
        showAlert.putExtra(INFO, sai);
        c.startService(showAlert);

        // save last alarm
        SharedPreferences.Editor prefs = PreferenceManager.getDefaultSharedPreferences(c).edit();
        prefs.putString("smart_alarm_last", DateUtils.formatDateSql(sai.getLectureStart()));
        prefs.apply();
    }

    private void handleToggle(Context c) {
        Utils.log("TOGGLE SMART ALARM");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
        SharedPreferences.Editor edit = prefs.edit();

        boolean status = !prefs.getBoolean("smart_alarm_active", false);
        edit.putBoolean("smart_alarm_active", status);
        edit.apply();

        if (status) {
            SmartAlarmUtils.scheduleAlarm(c);
        } else {
            SmartAlarmUtils.cancelAlarm(c);
            SmartAlarmUtils.updateWidget(c, null, false);
        }
    }
}
