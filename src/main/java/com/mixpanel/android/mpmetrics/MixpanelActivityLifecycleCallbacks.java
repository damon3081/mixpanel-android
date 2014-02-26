package com.mixpanel.android.mpmetrics;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

@TargetApi(14)
class MixpanelActivityLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {

    public MixpanelActivityLifecycleCallbacks(MixpanelAPI mpInstance) {
        this.mMpInstance = mpInstance;
    }

    /**
     * If MixpanelActivityLifecycleCallbacks is registered with the Application then this method
     * will be called anytime an activity is created. Our goal is to automatically check for and show
     * an eligible survey when the app is opened. Unfortunately, this also gets called every time
     * the device's orientation changes. We'll attempt to account for that by tracking the
     * orientation state and only checking for surveys if the orientation state did not change.
     * Furthermore, the Mixpanel library is unlikely to be instantiated in time for this to be called
     * on the initial opening of the application. However, this method is executed when the
     * application is in memory but closed and the user re-opens it.
     * back up.
     *
     * @param activity
     * @param savedInstanceState
     */
    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        if (! isActivityValid(activity)) {
            return;
        }

        final Configuration config = activity.getResources().getConfiguration();
        final boolean dueToOrientationChange = mCurOrientation != null && config.orientation != mCurOrientation;
        if(!dueToOrientationChange && activity.isTaskRoot()) {
            checkForInAppNotifications(activity);
            
            if (MPConfig.DEBUG) Log.d(LOGTAG, "checkForSurveys called from onActivityCreated");
            checkForSurveys(activity);
        }
        mCurOrientation = config.orientation;
    }

    /**
     * This method is called anytime an activity is started (which is quite frequently). The only
     * reason we are interested in this call is to check and show an eligible survey on initial app
     * open. Unfortunately, by the time MixpanelActivityLifecycleCallbacks is registered, we've
     * already missed the onActivityCreated call. We'll use this event to "catch up".
     * checkDecideService is only called if hasn't been previously called in the life of the app.
     *
     * @param activity
     */
    @Override
    public void onActivityStarted(Activity activity) {
        if (! isActivityValid(activity)) {
            return;
        }

        if (!mHasDoneFirstCheck && activity.isTaskRoot()) {
            mCurOrientation = activity.getResources().getConfiguration().orientation;
            
            checkForInAppNotifications(activity);
            
            if (MPConfig.DEBUG) Log.d(LOGTAG, "checkDecideService called from onActivityCreated");
            checkForSurveys(activity);
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {}

    @Override
    public void onActivityPaused(Activity activity) {}

    @Override
    public void onActivityStopped(Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

    @Override
    public void onActivityDestroyed(Activity activity) {}

    /**
     * Check for surveys and show one if applicable only if the activity is the task root.
     * We use activity.findViewById(android.R.id.content) to get the root view of the root activity
     * We instantiate a new SurveyCallbacks that auto-shows the survey.
     * @param activity
     */
    private void checkForSurveys(final Activity activity) {
        if (! ConfigurationChecker.checkSurveyActivityAvailable(activity.getApplicationContext())) {
            return;
        }

        final long startTime = System.currentTimeMillis();
        mHasDoneFirstCheck = true;
        mMpInstance.getPeople().checkForSurvey(new SurveyCallbacks() {
            @Override
            public void foundSurvey(final Survey survey) {
                final long endTime = System.currentTimeMillis();
                final long totalTime = endTime - startTime;
                if (null == survey) {
                    if (MPConfig.DEBUG) Log.d(LOGTAG, "No survey found, nothing to show the user.");
                } else if (totalTime <= timeoutMillis) { // If we're quick enough, just show the survey!
                    if (MPConfig.DEBUG) Log.d(LOGTAG, "found survey " + survey.getId() + ", calling showSurvey...");
                    mMpInstance.getPeople().showSurvey(survey, activity);
                } else {
                    final AlertDialog.Builder alertBuilder = new AlertDialog.Builder(activity);
                    alertBuilder.setTitle("We'd love your feedback!");
                    alertBuilder.setMessage("Mind taking a quick survey?");
                    alertBuilder.setPositiveButton("Sure", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            mMpInstance.getPeople().showSurvey(survey, activity);
                        }
                    });
                    alertBuilder.setNegativeButton("No, Thanks", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            // Don't hassle the user about this particular survey again.
                            mMpInstance.getPeople().append("$surveys", survey.getId());
                            mMpInstance.getPeople().append("$collections", survey.getCollectionId());
                        }
                    });
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            alertBuilder.show();
                        }
                    });
                }
            }
        }, activity);
    }
    
    private void checkForInAppNotifications(final Activity activity) {
        if (null == activity) {
            return;
        }
        
        mMpInstance.getPeople().checkForNotification(new InAppNotificationCallbacks() {
            @Override
            public void foundNotification(final InAppNotification n) {
                if (null == n) {
                    if (MPConfig.DEBUG) Log.d(LOGTAG, "No notification found, nothing to show the user.");
                    return;
                }
                mMpInstance.getPeople().showNotification(n, activity);
            }
        });
    }

    private boolean isActivityValid(Activity activity) {
        if (null == activity) {
            return false;
        }
        if (activity.isFinishing()) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= 17) {
            if (activity.isDestroyed()) {
                return false;
            }
        }

        return true;
    }

    private final MixpanelAPI mMpInstance;
    private boolean mHasDoneFirstCheck = false;
    private Integer mCurOrientation;
    private final long timeoutMillis = 2000; // 2 second timeout
    private static final String LOGTAG = "MixpanelAPI:MixpanelActivityLifecycleCallbacks";
}
