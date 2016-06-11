package de.tum.in.tumcampusapp.services;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.support.v4.content.LocalBroadcastManager;

import java.util.List;

import de.tum.in.tumcampusapp.R;
import de.tum.in.tumcampusapp.auxiliary.Const;
import de.tum.in.tumcampusapp.auxiliary.NetUtils;
import de.tum.in.tumcampusapp.auxiliary.Utils;
import de.tum.in.tumcampusapp.models.Location;
import de.tum.in.tumcampusapp.models.managers.CacheManager;
import de.tum.in.tumcampusapp.models.managers.CafeteriaManager;
import de.tum.in.tumcampusapp.models.managers.CafeteriaMenuManager;
import de.tum.in.tumcampusapp.models.managers.CardManager;
import de.tum.in.tumcampusapp.models.managers.KinoManager;
import de.tum.in.tumcampusapp.models.managers.NewsManager;
import de.tum.in.tumcampusapp.models.managers.OpenHoursManager;
import de.tum.in.tumcampusapp.models.managers.StudyRoomGroupManager;
import de.tum.in.tumcampusapp.models.managers.SurveyManager;
import de.tum.in.tumcampusapp.models.managers.SyncManager;
import de.tum.in.tumcampusapp.trace.G;
import de.tum.in.tumcampusapp.trace.Util;

/**
 * Service used to download files from external pages
 */
public class DownloadService extends IntentService {

    /**
     * Download broadcast identifier
     */
    public final static String BROADCAST_NAME = "de.tum.in.newtumcampus.intent.action.BROADCAST_DOWNLOAD";
    private static final String DOWNLOAD_SERVICE = "DownloadService";
    private static final String LAST_UPDATE = "last_update";
    private static final String CSV_LOCATIONS = "locations.csv";

    private final LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(this);

    /**
     * default init (run intent in new thread)
     */
    public DownloadService() {
        super(DOWNLOAD_SERVICE);
    }

    /**
     * Gets the time when BackgroundService was called last time
     *
     * @param c Context
     * @return time when BackgroundService was executed last time
     */
    public static long lastUpdate(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(Const.INTERNAL_PREFS, 0);
        return prefs.getLong(LAST_UPDATE, 0);
    }

    /**
     * Download the data for a specific intent
     * note, that only one concurrent download() is possible with a static synchronized method!
     */
    private static synchronized void download(Intent intent, DownloadService service) {
        //Set the app version if not set
        PackageInfo pi = Util.getPackageInfo(service);
        if (pi != null) {
            G.appVersion = pi.versionName; // Version
            G.appPackage = pi.packageName; // Package name
            G.appVersionCode = pi.versionCode; //Version code e.g.: 45
        }

        boolean successful = true;
        String action = intent.getStringExtra(Const.ACTION_EXTRA);
        boolean force = intent.getBooleanExtra(Const.FORCE_DOWNLOAD, false);
        boolean launch = intent.getBooleanExtra(Const.APP_LAUNCHES, false);

        // No action: leave service
        if (action == null) {
            return;
        }

        // Check if device has a internet connection

        boolean backgroundServicePermitted = Utils.isBackgroundServicePermitted(service);

        if (NetUtils.isConnected(service) && (launch || backgroundServicePermitted)) {
            Utils.logv("Handle action <" + action + ">");
            switch (action) {
                case Const.DOWNLOAD_ALL_FROM_EXTERNAL:
                    successful = service.downloadAll(force);

                    boolean isSetup = Utils.getInternalSettingBool(service, Const.EVERYTHING_SETUP, false);
                    if (isSetup) {
                        break;
                    }
                    CacheManager cm = new CacheManager(service);
                    cm.syncCalendar();
                    if (successful) {
                        Utils.setInternalSetting(service, Const.EVERYTHING_SETUP, true);
                    }
                    break;
                case Const.NEWS:
                    successful = service.downloadNews(force);
                    break;
                case Const.FACULTIES:
                    successful = service.downloadFaculties();
                    break;
                case Const.CAFETERIAS:
                    successful = service.downloadCafeterias(force);
                    break;
                case Const.KINO:
                    successful = service.downLoadKino(force);
                    break;
                case Const.STUDY_ROOMS:
                    successful = service.downloadStudyRooms();
                    break;
            }
        }

        if ((action.equals(Const.DOWNLOAD_ALL_FROM_EXTERNAL))) {
            try {
                service.importLocationsDefaults();
            } catch (Exception e) {
                Utils.log(e);
                successful = false;
            }
            if (successful) {
                SharedPreferences prefs = service.getSharedPreferences(Const.INTERNAL_PREFS, 0);
                prefs.edit().putLong(LAST_UPDATE, System.currentTimeMillis()).apply();
            }
            CardManager.update(service);
            successful = true;
        }

        // After done the job, create an broadcast intent and send it. The
        // receivers will be informed that the download service has finished.
        Utils.logv("Downloadservice was " + (successful ? "" : "not ") + "successful");
        if (successful) {
            service.broadcastDownloadCompleted();
        } else {
            service.broadcastError(service.getResources().getString(R.string.exception_unknown));
        }

        // Do all other import stuff that is not relevant for creating the viewing the start page
        if ((action.equals(Const.DOWNLOAD_ALL_FROM_EXTERNAL))) {
            service.startService(new Intent(service, FillCacheService.class));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Utils.log("DownloadService service has started");

        // Init sync table
        new SyncManager(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Utils.log("DownloadService service has stopped");
    }

    @Override
    protected void onHandleIntent(final Intent intent) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                download(intent, DownloadService.this);
            }
        }).start();
    }

    private void broadcastDownloadCompleted() {
        sendServiceBroadcast(Const.COMPLETED, null);
    }

    private void broadcastError(String message) {
        sendServiceBroadcast(Const.ERROR, message);
    }

    private void sendServiceBroadcast(String actionExtra, String message) {
        Intent intentSend = new Intent(BROADCAST_NAME)
                .putExtra(Const.ACTION_EXTRA, actionExtra);
        if (message != null) {
            intentSend.putExtra(Const.MESSAGE, message);
        }
        broadcastManager.sendBroadcast(intentSend);
    }

    /**
     * Download all external data and check, if the download was successful
     *
     * @param force True to force download over normal sync period
     * @return if all downloads were successful
     */
    private boolean downloadAll(boolean force) {
        final boolean cafe = downloadCafeterias(force),
                kino = downLoadKino(force),
                news = downloadNews(force),
                rooms = downloadStudyRooms(),
                faculties = downloadFaculties();
        return cafe && kino && news && rooms && faculties;
    }

    private boolean downloadCafeterias(boolean force) {
        try {
            CafeteriaManager cm = new CafeteriaManager(this);
            CafeteriaMenuManager cmm = new CafeteriaMenuManager(this);
            cm.downloadFromExternal(force);
            cmm.downloadFromExternal(this, force);
            return true;
        } catch (Exception e) {
            Utils.log(e);
            return false;
        }
    }

    private boolean downLoadKino(boolean force) {
        try {
            KinoManager km = new KinoManager(this);
            km.downloadFromExternal(force);
            return true;
        } catch (Exception e) {
            Utils.log(e);
            return false;
        }
    }

    private boolean downloadNews(boolean force) {
        try {
            NewsManager nm = new NewsManager(this);
            nm.downloadFromExternal(force);
            return true;
        } catch (Exception e) {
            Utils.log(e);
            return false;
        }
    }

    private boolean downloadFaculties() {
        try {
            SurveyManager sm = new SurveyManager(this);
            sm.downloadFacultiesFromExternal();
            sm.downLoadOpenQuestions();
            sm.downLoadOwnQuestions();
            return true;
        } catch (Exception e) {
            Utils.log(e);
            return false;
        }
    }

    private boolean downloadStudyRooms() {
        try {
            StudyRoomGroupManager sm = new StudyRoomGroupManager(this);
            sm.downloadFromExternal();
            return true;
        } catch (Exception e) {
            Utils.log(e);
            return false;
        }
    }

    /**
     * Import default location and opening hours from assets
     */
    private void importLocationsDefaults() throws Exception {
        OpenHoursManager lm = new OpenHoursManager(this);
        if (lm.empty()) {
            List<String[]> rows = Utils.readCsv(getAssets().open(CSV_LOCATIONS));

            for (String[] row : rows) {
                lm.replaceIntoDb(new Location(Integer.parseInt(row[0]), row[1],
                        row[2], row[3], row[4], row[5], row[6], row[7], row[8]));
            }
        }
    }
}
